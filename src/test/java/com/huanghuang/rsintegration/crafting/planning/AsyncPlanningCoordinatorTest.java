package com.huanghuang.rsintegration.crafting.planning;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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

    private static PlanningSnapshot snapshot() {
        return snapshot(UUID.randomUUID(), 1);
    }

    private static PlanningSnapshot snapshot(UUID player, long generation) {
        return new PlanningSnapshot(player, generation, 1,
                new ResourceLocation("test", "recipe"), Map.of(), Map.of(),
                new ImmutableRecipeGraph(Map.of()), "network", "binding", false);
    }
}
