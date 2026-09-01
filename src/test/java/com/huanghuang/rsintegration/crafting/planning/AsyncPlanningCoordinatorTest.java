package com.huanghuang.rsintegration.crafting.planning;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AsyncPlanningCoordinatorTest extends BootstrapTest {
    @Test
    void staleResultRollsBackInsteadOfCommitting() throws Exception {
        try (AsyncPlanningCoordinator coordinator = new AsyncPlanningCoordinator(1)) {
            CountDownLatch completed = new CountDownLatch(1);
            AtomicInteger commits = new AtomicInteger();
            AtomicInteger rollbacks = new AtomicInteger();
            coordinator.submit(snapshot(), ignored -> "plan", Runnable::run,
                    ignored -> false, ignored -> commits.incrementAndGet(), ignored -> {
                        rollbacks.incrementAndGet();
                        completed.countDown();
                    });
            assertTrue(completed.await(2, TimeUnit.SECONDS));
            assertEquals(0, commits.get());
            assertEquals(1, rollbacks.get());
        }
    }

    @Test
    void newerRequestCancelsOlderRequest() throws Exception {
        try (AsyncPlanningCoordinator coordinator = new AsyncPlanningCoordinator(1)) {
            UUID player = UUID.randomUUID();
            CountDownLatch allowFirst = new CountDownLatch(1);
            CountDownLatch completed = new CountDownLatch(2);
            AtomicInteger commits = new AtomicInteger();
            AtomicInteger rollbacks = new AtomicInteger();
            coordinator.submit(snapshot(player, 1), ignored -> {
                try { allowFirst.await(2, TimeUnit.SECONDS); } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
                return "old";
            }, Runnable::run, ignored -> true, ignored -> commits.incrementAndGet(), ignored -> {
                rollbacks.incrementAndGet(); completed.countDown();
            });
            coordinator.submit(snapshot(player, 2), ignored -> "new", Runnable::run,
                    ignored -> true, ignored -> { commits.incrementAndGet(); completed.countDown(); },
                    ignored -> { rollbacks.incrementAndGet(); completed.countDown(); });
            allowFirst.countDown();
            assertTrue(completed.await(2, TimeUnit.SECONDS));
            assertEquals(1, commits.get());
            assertEquals(1, rollbacks.get());
        }
    }

    @Test
    void identicalSharedRequestRunsOnceAndUsesLatestSnapshotForCommit() throws Exception {
        try (AsyncPlanningCoordinator coordinator = new AsyncPlanningCoordinator(1)) {
            UUID player = UUID.randomUUID();
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            CountDownLatch committed = new CountDownLatch(1);
            AtomicInteger computations = new AtomicInteger();
            AtomicReference<PlanningSnapshot> callbackSnapshot = new AtomicReference<>();
            Object key = "same-request";
            coordinator.submitShared(key, snapshot(player, 1), ignored -> {
                computations.incrementAndGet();
                started.countDown();
                try { release.await(2, TimeUnit.SECONDS); } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
                return "plan";
            }, Runnable::run, ignored -> true,
                    (usedSnapshot, result) -> {
                        callbackSnapshot.set(usedSnapshot);
                        committed.countDown();
                    }, failure -> committed.countDown());
            assertTrue(started.await(2, TimeUnit.SECONDS));
            coordinator.submitShared(key, snapshot(player, 2), ignored -> {
                computations.incrementAndGet();
                return "unexpected";
            }, Runnable::run, ignored -> true,
                    (usedSnapshot, result) -> {
                        callbackSnapshot.set(usedSnapshot);
                        committed.countDown();
                    }, failure -> committed.countDown());
            release.countDown();
            assertTrue(committed.await(2, TimeUnit.SECONDS));
            assertEquals(1, computations.get());
            assertEquals(2, callbackSnapshot.get().requestGeneration());
        }
    }

    @Test
    void repeatedCancellationPublishesOneRollback() throws Exception {
        try (AsyncPlanningCoordinator coordinator = new AsyncPlanningCoordinator(1)) {
            UUID player = UUID.randomUUID();
            CountDownLatch workerStarted = new CountDownLatch(1);
            CountDownLatch releaseWorker = new CountDownLatch(1);
            CountDownLatch rolledBack = new CountDownLatch(1);
            AtomicInteger rollbacks = new AtomicInteger();
            coordinator.submit(snapshot(player, 1), ignored -> {
                workerStarted.countDown();
                try {
                    releaseWorker.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
                return null;
            }, Runnable::run, ignored -> true, ignored -> {}, failure -> {
                rollbacks.incrementAndGet();
                rolledBack.countDown();
            });
            assertTrue(workerStarted.await(2, TimeUnit.SECONDS));

            coordinator.cancel(player);
            coordinator.cancel(player);
            releaseWorker.countDown();

            assertTrue(rolledBack.await(2, TimeUnit.SECONDS));
            assertEquals(1, rollbacks.get());
        }
    }

    @Test
    void snapshotCopiesMutableInputsAndCapturesRevision() {
        Map<com.huanghuang.rsintegration.crafting.CraftingResolver.StackKey, Integer> items =
                new java.util.HashMap<>();
        PlanningSnapshot snapshot = PlanningSnapshotFactory.capture(UUID.randomUUID(), 1,
                new ResourceLocation("test", "recipe"), items, new java.util.HashMap<>(),
                new ImmutableRecipeGraph(Map.of()), null, null, false);
        items.clear();
        assertTrue(snapshot.availableItems().isEmpty());
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.availableItems().put(null, 1));
    }

    @Test
    void thirdPartyBoundaryUsesServerFallback() throws Exception {
        try (AsyncPlanningCoordinator coordinator = new AsyncPlanningCoordinator(1)) {
            CountDownLatch completed = new CountDownLatch(1);
            AtomicInteger commits = new AtomicInteger();
            coordinator.submit(snapshot(), ignored -> {
                        throw new PlanningThreadContext.MainThreadPlanningFallbackException("mod recipe");
                    }, Runnable::run, ignored -> true, result -> {
                        assertEquals("sync", result);
                        commits.incrementAndGet(); completed.countDown();
                    }, failure -> completed.countDown(), ignored -> "sync");
            assertTrue(completed.await(2, TimeUnit.SECONDS));
            assertEquals(1, commits.get());
        }
    }

    @Test
    void supersededQueuedRequestIsRemovedBeforeReplacementIsSubmitted() throws Exception {
        try (AsyncPlanningCoordinator coordinator = new AsyncPlanningCoordinator(
                1, 1, runnable -> new Thread(runnable, "planner-test"))) {
            UUID workerPlayer = UUID.randomUUID();
            UUID queuedPlayer = UUID.randomUUID();
            CountDownLatch workerStarted = new CountDownLatch(1);
            CountDownLatch releaseWorker = new CountDownLatch(1);
            CountDownLatch completed = new CountDownLatch(3);
            AtomicInteger oldComputations = new AtomicInteger();
            AtomicInteger newComputations = new AtomicInteger();
            AtomicInteger commits = new AtomicInteger();
            AtomicInteger rollbacks = new AtomicInteger();

            coordinator.submit(snapshot(workerPlayer, 1), ignored -> {
                workerStarted.countDown();
                try {
                    releaseWorker.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
                return "worker";
            }, Runnable::run, ignored -> true,
                    ignored -> { commits.incrementAndGet(); completed.countDown(); },
                    ignored -> { rollbacks.incrementAndGet(); completed.countDown(); });
            assertTrue(workerStarted.await(2, TimeUnit.SECONDS));

            coordinator.submit(snapshot(queuedPlayer, 1), ignored -> {
                oldComputations.incrementAndGet();
                return "old";
            }, Runnable::run, ignored -> true,
                    ignored -> { commits.incrementAndGet(); completed.countDown(); },
                    ignored -> { rollbacks.incrementAndGet(); completed.countDown(); });
            coordinator.submit(snapshot(queuedPlayer, 2), ignored -> {
                newComputations.incrementAndGet();
                return "new";
            }, Runnable::run, ignored -> true,
                    ignored -> { commits.incrementAndGet(); completed.countDown(); },
                    ignored -> { rollbacks.incrementAndGet(); completed.countDown(); });

            releaseWorker.countDown();
            assertTrue(completed.await(2, TimeUnit.SECONDS));
            assertEquals(0, oldComputations.get());
            assertEquals(1, newComputations.get());
            assertEquals(2, commits.get());
            assertEquals(1, rollbacks.get());
        }
    }

    @Test
    void fullQueueRejectsWithoutRunningComputationOnCaller() throws Exception {
        try (AsyncPlanningCoordinator coordinator = new AsyncPlanningCoordinator(
                1, 1, runnable -> new Thread(runnable, "planner-test"))) {
            CountDownLatch workerStarted = new CountDownLatch(1);
            CountDownLatch releaseWorker = new CountDownLatch(1);
            CountDownLatch rejected = new CountDownLatch(1);
            AtomicInteger rejectedComputations = new AtomicInteger();
            AtomicReference<Throwable> rejection = new AtomicReference<>();

            coordinator.submit(snapshot(UUID.randomUUID(), 1), ignored -> {
                workerStarted.countDown();
                try {
                    releaseWorker.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
                return "worker";
            }, Runnable::run, ignored -> true, ignored -> {}, ignored -> {});
            assertTrue(workerStarted.await(2, TimeUnit.SECONDS));
            coordinator.submit(snapshot(UUID.randomUUID(), 1), ignored -> "queued",
                    Runnable::run, ignored -> true, ignored -> {}, ignored -> {});

            coordinator.submit(snapshot(UUID.randomUUID(), 1), ignored -> {
                rejectedComputations.incrementAndGet();
                return "rejected";
            }, Runnable::run, ignored -> true, ignored -> {}, failure -> {
                rejection.set(failure);
                rejected.countDown();
            });

            assertTrue(rejected.await(2, TimeUnit.SECONDS));
            assertEquals(0, rejectedComputations.get());
            assertTrue(rejection.get() instanceof RejectedExecutionException);
            releaseWorker.countDown();
        }
    }

    private static PlanningSnapshot snapshot() {
        return snapshot(UUID.randomUUID(), 1);
    }

    private static PlanningSnapshot snapshot(UUID player, long generation) {
        return new PlanningSnapshot(player, generation, 1,
                new ResourceLocation("test", "recipe"), Map.of(), Map.of(),
                new ImmutableRecipeGraph(Map.of()), "network", "binding", false);
    }
}
