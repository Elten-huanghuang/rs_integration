package com.huanghuang.rsintegration.crafting.planning;

import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/** Owns cancellation and the background-to-server-thread handoff for preview planning. */
public final class AsyncPlanningCoordinator implements AutoCloseable {
    private static final AtomicInteger THREAD_IDS = new AtomicInteger();
    private final ExecutorService workers;
    private final ConcurrentHashMap<UUID, Request<?>> active = new ConcurrentHashMap<>();

    public AsyncPlanningCoordinator(int parallelism) {
        this(parallelism, runnable -> {
            Thread thread = new Thread(runnable, "rsi-planner-" + THREAD_IDS.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    AsyncPlanningCoordinator(int parallelism, ThreadFactory threadFactory) {
        workers = Executors.newFixedThreadPool(Math.max(1, parallelism), threadFactory);
    }

    /**
     * The caller must capture {@code snapshot} on the server thread. Computation may only inspect
     * that snapshot; validation, commit and rollback are dispatched through {@code serverExecutor}.
     */
    public <T> void submit(PlanningSnapshot snapshot,
                           Function<PlanningSnapshot, T> computation,
                           Executor serverExecutor,
                           Predicate<PlanningSnapshot> revalidator,
                           Consumer<T> commit,
                           Consumer<Throwable> rollback) {
        submit(snapshot, computation, serverExecutor, revalidator, commit, rollback, null);
    }

    /**
     * Variant with an explicit server-thread fallback. This is used while migrating
     * recipes whose handlers still require live third-party objects.
     */
    public <T> void submit(PlanningSnapshot snapshot,
                           Function<PlanningSnapshot, T> computation,
                           Executor serverExecutor,
                           Predicate<PlanningSnapshot> revalidator,
                           Consumer<T> commit,
                           Consumer<Throwable> rollback,
                           Function<PlanningSnapshot, T> synchronousFallback) {
        Request<T> request = new Request<>(snapshot, serverExecutor, rollback);
        Request<?> previous = active.put(snapshot.playerId(), request);
        if (previous != null) previous.cancel();

        CompletableFuture<T> future = CompletableFuture.supplyAsync(() -> {
            if (request.cancelled) throw new CancellationException();
            return PlanningThreadContext.runInBackground(() -> computation.apply(snapshot));
        }, workers);
        request.future = future;
        future.whenComplete((result, failure) -> serverExecutor.execute(() -> {
            if (!active.remove(snapshot.playerId(), request)) return;
            if (!request.finish()) return;
            Throwable cause = unwrap(failure);
            if (cause != null) {
                if (synchronousFallback != null
                        && cause instanceof PlanningThreadContext.MainThreadPlanningFallbackException
                        && !request.cancelled && revalidator.test(snapshot)) {
                    try {
                        commit.accept(synchronousFallback.apply(snapshot));
                    } catch (Throwable fallbackFailure) {
                        rollback.accept(fallbackFailure);
                    }
                    return;
                }
                rollback.accept(cause);
                return;
            }
            if (!revalidator.test(snapshot)) {
                rollback.accept(new StalePlanningResultException(snapshot));
                return;
            }
            try {
                commit.accept(result);
            } catch (Throwable commitFailure) {
                rollback.accept(commitFailure);
            }
        }));
    }

    public void cancel(UUID playerId) {
        Request<?> request = active.remove(playerId);
        if (request != null) request.cancel();
    }

    public void cancelAll() {
        active.forEach((id, request) -> request.cancel());
        active.clear();
    }

    public int activeCount() {
        return active.size();
    }

    @Override
    public void close() {
        cancelAll();
        workers.shutdownNow();
    }

    private static Throwable unwrap(Throwable failure) {
        if (failure == null) return null;
        return failure instanceof java.util.concurrent.CompletionException && failure.getCause() != null
                ? failure.getCause() : failure;
    }

    private static final class Request<T> {
        private final PlanningSnapshot snapshot;
        private final Executor serverExecutor;
        private final Consumer<Throwable> rollback;
        private final AtomicBoolean terminal = new AtomicBoolean();
        private volatile boolean cancelled;
        private volatile CompletableFuture<T> future;

        private Request(PlanningSnapshot snapshot, Executor serverExecutor, Consumer<Throwable> rollback) {
            this.snapshot = snapshot;
            this.serverExecutor = serverExecutor;
            this.rollback = rollback;
        }

        private void cancel() {
            cancelled = true;
            CompletableFuture<T> current = future;
            if (current != null) current.cancel(true);
            serverExecutor.execute(() -> {
                if (finish()) rollback.accept(new CancellationException(
                        "Planning request cancelled for " + snapshot.recipeId()));
            });
        }

        private boolean finish() {
            return terminal.compareAndSet(false, true);
        }
    }

    public static final class StalePlanningResultException extends RuntimeException {
        public StalePlanningResultException(PlanningSnapshot snapshot) {
            super("Planning result is stale for " + snapshot.recipeId()
                    + " (generation " + snapshot.requestGeneration() + ")");
        }
    }
}
