package com.huanghuang.rsintegration.crafting.planning;

import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.IngredientRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.MaterialRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.RecipeNode;
import org.junit.jupiter.api.Test;
import net.minecraft.resources.ResourceLocation;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class PlanRequestServiceTest {
    @Test
    void shortBranchClassificationBoundsCatalogueSlotsAndAlternatives() {
        var raw = new MaterialRef(ResourceLocation.parse("test:ore"), "");
        var output = new MaterialRef(ResourceLocation.parse("test:ingot"), "");
        var input = new IngredientRef(List.of(raw), 1);
        assertTrue(branch(output, List.of(input), Map.of(raw, 1L), 1).fitsShortSlice());
        assertFalse(branch(output, Collections.nCopies(10, input), Map.of(raw, 10L), 1).fitsShortSlice());
        assertFalse(branch(output, List.of(new IngredientRef(List.of(raw, output), 1)), Map.of(), 1).fitsShortSlice());
        assertFalse(branch(output, List.of(input), Map.of(), 2).fitsShortSlice());
    }

    @Test
    void delayedShortBranchHandoffChecksGenerationBeforeAdmittingCandidate() throws Exception {
        var raw = new MaterialRef(ResourceLocation.parse("test:ore"), "");
        var output = new MaterialRef(ResourceLocation.parse("test:ingot"), "");
        var branch = branch(output, List.of(new IngredientRef(List.of(raw), 1)), Map.of(raw, 1L), 1);
        try (var service = new PlanRequestService(1, 2)) {
            var player = UUID.randomUUID();
            var server = new LinkedBlockingQueue<Runnable>();
            var rejected = new AtomicInteger();
            var accepted = new AtomicInteger();
            for (boolean expire : List.of(true, false)) {
                long generation = service.begin(player);
                var snapshot = PlanningSnapshotFactory.capture(player, generation, output.itemId(), Map.of(),
                        Map.of(), new ImmutableRecipeGraph(Map.of()), "", "", false);
                service.branchDispatcher(snapshot, server::add).submit(branch, candidate -> {
                    if (candidate == null) rejected.incrementAndGet();
                    else accepted.incrementAndGet();
                });
                var delivered = server.poll(2, TimeUnit.SECONDS);
                assertNotNull(delivered);
                if (expire) assertTrue(service.complete(player, generation));
                delivered.run();
                if (!expire) assertTrue(service.complete(player, generation));
            }
            assertEquals(1, accepted.get());
            assertEquals(1, rejected.get());
        }
    }

    private static FrozenRecipeBranch branch(MaterialRef output, List<IngredientRef> inputs,
                                             Map<MaterialRef, Long> stock, int steps) {
        var recipe = new RecipeNode(ResourceLocation.parse("test:smelt"), output, 1, inputs);
        return new FrozenRecipeBranch(Map.of(output, List.of(recipe)), stock,
                new IngredientRef(List.of(output), 1), steps, 4096);
    }

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
