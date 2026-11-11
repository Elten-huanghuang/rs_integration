package com.huanghuang.rsintegration.crafting.planning;

import org.junit.jupiter.api.Test;
import net.minecraft.resources.ResourceLocation;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

class PlanRequestServiceTest {
    @Test
    void cancellationStillStopsComputationAfterTerminalPublicationReleasedIdentity() throws Exception {
        try (var service = new PlanRequestService(1, 1)) {
            UUID player = UUID.randomUUID();
            long generation = service.begin(player);
            var field = PlanRequestService.class.getDeclaredField("coordinator");
            field.setAccessible(true);
            var coordinator = (AsyncPlanningCoordinator) field.get(service);
            var snapshot = new PlanningSnapshot(player, generation, 1L,
                    new ResourceLocation("test", "recipe"), Map.of(), Map.of(),
                    new ImmutableRecipeGraph(Map.of()), "network", "binding", false);
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch exited = new CountDownLatch(1);
            AtomicInteger commits = new AtomicInteger();
            coordinator.submit(snapshot, ignored -> {
                started.countDown();
                try { while (true) PlanningThreadContext.throwIfCancelled(); }
                finally { exited.countDown(); }
            }, Runnable::run, ignored -> true, ignored -> commits.incrementAndGet(), ignored -> {});
            assertTrue(started.await(2, TimeUnit.SECONDS));
            assertTrue(service.complete(player, generation));
            assertFalse(service.cancel(player, generation));
            assertTrue(exited.await(2, TimeUnit.SECONDS));
            assertEquals(0, coordinator.activeCount());
            assertEquals(0, commits.get());
        }
    }

    @Test
    void capacityIsReleasedOnCompletionAndCancellationWithoutReusingIds() {
        try (var service = new PlanRequestService(1, 1)) {
            UUID player = UUID.randomUUID();
            long first = service.begin(player);
            long second = service.begin(player);
            assertEquals(0L, service.begin(player));
            assertTrue(service.complete(player, first));
            long third = service.begin(player);
            assertTrue(third > second);
            assertTrue(service.cancel(player, second));
            assertFalse(service.isCurrent(player, second));
            assertTrue(service.isCurrent(player, third));
        }
    }

    @Test
    void independentGenerationsAreCancelledIndividuallyAndForgetClearsAll() {
        try (PlanRequestService service = new PlanRequestService(1)) {
            UUID player = UUID.randomUUID();
            long first = service.begin(player);
            assertTrue(service.isCurrent(player, first));
            long second = service.begin(player);
            assertTrue(service.isCurrent(player, first));
            assertTrue(service.isCurrent(player, second));
            assertTrue(service.cancel(player, first));
            assertFalse(service.cancel(player, first));
            assertFalse(service.isCurrent(player, first));
            assertTrue(service.isCurrent(player, second));
            service.forget(player);
            assertFalse(service.isCurrent(player, second));
        }
    }
}
