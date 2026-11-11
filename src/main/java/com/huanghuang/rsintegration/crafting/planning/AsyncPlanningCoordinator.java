package com.huanghuang.rsintegration.crafting.planning;

import com.huanghuang.rsintegration.util.PerformanceMonitor;
import com.huanghuang.rsintegration.config.CraftingPlanningConfig;
import java.util.concurrent.CompletionException;

import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Predicate;

/** Owns cancellation and the background-to-server-thread handoff for preview planning. */
public final class AsyncPlanningCoordinator implements AutoCloseable {
    private static final AtomicInteger THREAD_IDS = new AtomicInteger();
    private final CooperativePlanningExecutor workers;
    private final ConcurrentHashMap<PlanningRequestKey, Request<?>> active = new ConcurrentHashMap<>();

    public AsyncPlanningCoordinator(int parallelism) {
        this(parallelism, runnable -> {
            Thread thread = new Thread(runnable, "rsi-planner-" + THREAD_IDS.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    public AsyncPlanningCoordinator(int parallelism, int queueCapacity) {
        this(parallelism, queueCapacity, runnable -> {
            Thread thread = new Thread(runnable, "rsi-planner-" + THREAD_IDS.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    AsyncPlanningCoordinator(int parallelism, ThreadFactory threadFactory) {
        this(parallelism, CraftingPlanningConfig.DEFAULT_QUEUE_CAPACITY, threadFactory);
    }

    AsyncPlanningCoordinator(int parallelism, int queueCapacity, ThreadFactory threadFactory) {
        this(parallelism, queueCapacity, CraftingPlanningConfig.DEFAULT_SLICE_MS,
                CraftingPlanningConfig.DEFAULT_BACKGROUND_TIMEOUT_MS, threadFactory);
    }

    public AsyncPlanningCoordinator(int parallelism, int queueCapacity, int sliceMs, int timeoutMs) {
        this(parallelism, queueCapacity, sliceMs, timeoutMs, runnable -> {
            Thread thread = new Thread(runnable, "rsi-planner-" + THREAD_IDS.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    private AsyncPlanningCoordinator(int parallelism, int queueCapacity, int sliceMs,
                                     int timeoutMs, ThreadFactory threadFactory) {
        workers = new CooperativePlanningExecutor(Math.max(1, parallelism), Math.max(1, queueCapacity),
                sliceMs, CraftingPlanningConfig.PERIOD_MS, timeoutMs, threadFactory);
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
        submitInternal(null, snapshot, computation, serverExecutor, revalidator,
                (ignored, result) -> commit.accept(result), rollback);
    }

    /**
     * Submits a computation that may be shared by identical immutable requests. The latest
     * callbacks replace the previous callbacks, so a duplicate preview receives exactly one
     * response while the expensive worker computation runs only once.
     */
    public <T> void submitShared(Object key, PlanningSnapshot snapshot,
                                 Function<PlanningSnapshot, T> computation,
                                 Executor serverExecutor,
                                 Predicate<PlanningSnapshot> revalidator,
                                 Consumer<T> commit, Consumer<Throwable> rollback) {
        submitInternal(key, snapshot, computation, serverExecutor, revalidator,
                (ignored, result) -> commit.accept(result), rollback);
    }

    public <T> void submitShared(Object key, PlanningSnapshot snapshot,
                                 Function<PlanningSnapshot, T> computation,
                                 Executor serverExecutor,
                                 Predicate<PlanningSnapshot> revalidator,
                                 BiConsumer<PlanningSnapshot, T> commit,
                                 Consumer<Throwable> rollback) {
        submitInternal(key, snapshot, computation, serverExecutor, revalidator, commit, rollback);
    }

    private <T> void submitInternal(Object sharedKey, PlanningSnapshot snapshot,
                           Function<PlanningSnapshot, T> computation,
                           Executor serverExecutor,
                           Predicate<PlanningSnapshot> revalidator,
                           BiConsumer<PlanningSnapshot, T> commit, Consumer<Throwable> rollback) {
        PlanningRequestKey requestKey = requestKey(snapshot.playerId(), snapshot.requestGeneration());
        if (sharedKey != null) {
            Request<?> existing = active.get(requestKey);
            if (existing != null && sharedKey.equals(existing.sharedKey())) {
                @SuppressWarnings("unchecked") Request<T> same = (Request<T>) existing;
                same.replaceHandlers(snapshot, revalidator, commit, rollback);
                return;
            }
        }
        Request<T> request = new Request<>(sharedKey, snapshot, serverExecutor, rollback, workers,
                revalidator, commit);
        Request<?> previous = active.put(requestKey, request);
        if (previous != null) previous.cancel();

        try {
            Future<?> task = workers.submit(() -> execute(request, computation));
            request.attach(task);
            PerformanceMonitor.recordPlanningSubmitted(workers.getActiveCount(), workers.queuedCount());
        } catch (RejectedExecutionException rejected) {
            active.remove(requestKey, request);
            PerformanceMonitor.recordPlanningRejected(workers.getActiveCount(), workers.queuedCount());
            request.reject(rejected);
        }
    }

    private <T> void execute(Request<T> request, Function<PlanningSnapshot, T> computation) {
        long started = System.nanoTime();
        PerformanceMonitor.recordPlanningLatency(PerformanceMonitor.PlanningLatencyPhase.QUEUE_WAIT,
                started - request.submittedNanos);
        T result = null;
        Throwable failure = null;
        try {
            if (request.isCancelled()) throw new CancellationException();
            result = PlanningThreadContext.runInBackground(() -> computation.apply(request.snapshot));
        } catch (Throwable thrown) {
            failure = thrown;
        } finally {
            int queued = workers.queuedCount();
            int activeAfterCompletion = Math.max(0, workers.getActiveCount() - 1);
            PerformanceMonitor.recordPlanningExecution(System.nanoTime() - started,
                    activeAfterCompletion, queued);
        }

        T completedResult = result;
        Throwable completedFailure = failure;
        long handoffStarted = System.nanoTime();
        request.serverExecutor.execute(() -> {
            PlanningSnapshot snapshot = request.callbackSnapshot;
            if (!active.remove(requestKey(request.snapshot.playerId(), request.snapshot.requestGeneration()), request)) return;
            if (!request.finish()) return;
            PerformanceMonitor.recordPlanningLatency(PerformanceMonitor.PlanningLatencyPhase.HANDOFF_WAIT,
                    System.nanoTime() - handoffStarted);
            Throwable cause = unwrap(completedFailure);
            Predicate<PlanningSnapshot> revalidator = request.revalidator;
            BiConsumer<PlanningSnapshot, T> commit = request.commit;
            if (cause != null) {
                request.rollback.accept(cause);
                return;
            }
            if (!revalidator.test(snapshot)) {
                request.rollback.accept(new StalePlanningResultException(snapshot));
                return;
            }
            try {
                commit.accept(snapshot, completedResult);
            } catch (Throwable commitFailure) {
                request.rollback.accept(commitFailure);
            }
        });
    }

    public void cancel(UUID playerId) {
        active.forEach((key, request) -> {
            if (request.snapshot.playerId().equals(playerId) && active.remove(key, request)) request.cancel();
        });
    }

    public void cancel(UUID playerId, long generation) {
        Request<?> request = active.remove(requestKey(playerId, generation));
        if (request != null) request.cancel();
    }

    private PlanningRequestKey requestKey(UUID playerId, long generation) {
        return new PlanningRequestKey(playerId, generation);
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
        PerformanceMonitor.recordPlanningExecutorState(0, 0);
    }

    private static Throwable unwrap(Throwable failure) {
        if (failure == null) return null;
        return failure instanceof CompletionException && failure.getCause() != null
                ? failure.getCause() : failure;
    }

    private static final class Request<T> {
        private enum State {
            ACTIVE,
            CANCEL_REQUESTED,
            CANCELLED,
            TERMINAL;

            private boolean cancelled() {
                return this == CANCEL_REQUESTED || this == CANCELLED;
            }
        }

        private final Object sharedKey;
        private final long submittedNanos = System.nanoTime();
        private final PlanningSnapshot snapshot;
        private volatile PlanningSnapshot callbackSnapshot;
        private final Executor serverExecutor;
        private volatile Consumer<Throwable> rollback;
        private final CooperativePlanningExecutor workers;
        private volatile Predicate<PlanningSnapshot> revalidator;
        private volatile BiConsumer<PlanningSnapshot, T> commit;
        private final AtomicReference<State> state = new AtomicReference<>(State.ACTIVE);
        private volatile Future<?> task;

        private Request(Object sharedKey, PlanningSnapshot snapshot, Executor serverExecutor,
                        Consumer<Throwable> rollback, CooperativePlanningExecutor workers,
                        Predicate<PlanningSnapshot> revalidator,
                        BiConsumer<PlanningSnapshot, T> commit) {
            this.sharedKey = sharedKey;
            this.snapshot = snapshot;
            this.callbackSnapshot = snapshot;
            this.serverExecutor = serverExecutor;
            this.rollback = rollback;
            this.workers = workers;
            this.revalidator = revalidator;
            this.commit = commit;
        }

        private Object sharedKey() { return sharedKey; }

        private void replaceHandlers(PlanningSnapshot latestSnapshot, Predicate<PlanningSnapshot> validator,
                                     BiConsumer<PlanningSnapshot, T> nextCommit,
                                     Consumer<Throwable> nextRollback) {
            // The server executor is stable for a player, but retain the original executor used
            // to schedule the worker handoff. Only callback state is replaced for deduplication.
            this.callbackSnapshot = latestSnapshot;
            this.revalidator = validator;
            this.commit = nextCommit;
            this.rollback = nextRollback;
        }

        private void attach(Future<?> submittedTask) {
            task = submittedTask;
            if (state.get().cancelled()) cancelTask(submittedTask);
        }

        private void cancel() {
            if (!state.compareAndSet(State.ACTIVE, State.CANCEL_REQUESTED)) return;
            PerformanceMonitor.recordPlanningCancelled(workers.getActiveCount(), workers.queuedCount());
            Future<?> current = task;
            if (current != null) cancelTask(current);
            serverExecutor.execute(() -> {
                if (finishCancellation()) rollback.accept(new CancellationException(
                        "Planning request cancelled for " + snapshot.recipeId()));
            });
        }

        private void cancelTask(Future<?> current) {
            current.cancel(true);
            if (current instanceof Runnable queued) workers.remove(queued);
            PerformanceMonitor.recordPlanningExecutorState(workers.getActiveCount(), workers.queuedCount());
        }

        private void reject(RejectedExecutionException failure) {
            serverExecutor.execute(() -> {
                if (finish()) rollback.accept(failure);
            });
        }

        private boolean isCancelled() {
            return state.get().cancelled();
        }

        private boolean finish() {
            return state.compareAndSet(State.ACTIVE, State.TERMINAL);
        }

        private boolean finishCancellation() {
            return state.compareAndSet(State.CANCEL_REQUESTED, State.CANCELLED);
        }
    }

    public static final class StalePlanningResultException extends RuntimeException {
        public StalePlanningResultException(PlanningSnapshot snapshot) {
            super("Planning result is stale for " + snapshot.recipeId()
                    + " (generation " + snapshot.requestGeneration() + ")");
        }
    }
}
