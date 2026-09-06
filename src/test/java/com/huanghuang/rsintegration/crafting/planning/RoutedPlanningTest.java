package com.huanghuang.rsintegration.crafting.planning;

import com.huanghuang.rsintegration.command.PerformanceMonitor;
import com.huanghuang.rsintegration.crafting.CraftingResolver.StackKey;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.IngredientRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.MaterialRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.RecipeNode;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class RoutedPlanningTest extends BootstrapTest {
    private static final ResourceLocation TARGET = new ResourceLocation("test:target");
    private static final MaterialRef IRON = new MaterialRef(new ResourceLocation("minecraft:iron_ingot"), "");
    private static final MaterialRef RESULT = new MaterialRef(new ResourceLocation("minecraft:iron_block"), "");

    @Test
    void inspectionAndSearchFinishBeforeOneServerHandoff() throws Exception {
        PlanningSnapshot snapshot = snapshot(1, false, false);
        var routing = routing(snapshot, Set.of(), 512);
        var callbacks = new LinkedBlockingQueue<Runnable>();
        var result = new AtomicReference<AsyncPurePlanningService.RoutedPlan>();
        var failure = new AtomicReference<Throwable>();
        try (var coordinator = new AsyncPlanningCoordinator(1)) {
            long inspectedBefore = PerformanceMonitor.planningLatency(
                    PerformanceMonitor.PlanningLatencyPhase.HANDOFF_WAIT).calls();
            new AsyncPurePlanningService(coordinator).submitRouted(snapshot, routing, 1,
                    callbacks::add, 30, 1000, 100, 1500, result::set, failure::set);
            Runnable handoff = callbacks.poll(5, TimeUnit.SECONDS);
            assertNotNull(handoff);
            assertNull(result.get());
            assertTrue(callbacks.isEmpty());
            handoff.run();
            assertNull(failure.get());
            assertNotNull(result.get());
            assertTrue(result.get().plan().feasible());
            assertEquals(PureDemandTreeInspector.Status.COMPLETE, result.get().inspection().status());
            assertEquals(inspectedBefore + 1, PerformanceMonitor.planningLatency(
                    PerformanceMonitor.PlanningLatencyPhase.HANDOFF_WAIT).calls());
            assertFalse(PlanningLookupCache.isActive());
        }
    }

    @Test
    void combinedJobMatchesSeparateInspectorAndPlanner() {
        PlanningSnapshot snapshot = snapshot(1, false, false);
        var routing = routing(snapshot, Set.of(), 512);
        var expectedTree = PureDemandTreeInspector.inspect(snapshot.recipeGraph(), routing.available(),
                TARGET, 1, 512, Set.of(), Set.of(), Set.of());
        var bound = ImmutableRecipeGraphProjector.bindAvailability(snapshot.recipeGraph(), routing.available());
        var expected = PureRecipePlanner.resolve(bound, routing.available(),
                bound.recipesById().get(TARGET).inputs(), 30, 1000, 100, Long.MAX_VALUE);
        var actual = compute(snapshot, routing, 1);
        assertEquals(expectedTree, actual.inspection());
        assertEquals(expected, actual.plan());
    }

    @Test
    void incompleteProjectionReturnsRouteWithoutInventingMissingPlan() {
        var snapshot = snapshot(0, false, false);
        var routed = compute(snapshot, routing(snapshot, Set.of(IRON.itemId()), 512), 1);
        assertEquals(PureDemandTreeInspector.Status.UNPROJECTED_DEPENDENCY, routed.inspection().status());
        assertNull(routed.plan());
    }

    @Test
    void missingRawMaterialStillUsesPurePlanner() {
        var snapshot = snapshot(0, false, false);
        var routed = compute(snapshot, routing(snapshot, Set.of(), 512), 1);
        assertEquals(PureDemandTreeInspector.Status.MISSING_MATERIALS, routed.inspection().status());
        assertNotNull(routed.plan());
        assertFalse(routed.plan().feasible());
    }

    @Test
    void targetOutsideProjectionKeepsTypedRoute() {
        var original = snapshot(1, false, false);
        var empty = new PlanningSnapshot(original.playerId(), 1, 1, TARGET, original.availableItems(),
                Map.of(), new ImmutableRecipeGraph(Map.of()), "network", "binding", false);
        var routed = compute(empty, routing(empty, Set.of(), 512), 1);
        assertEquals(PureDemandTreeInspector.Status.TARGET_NOT_PROJECTED, routed.inspection().status());
        assertNull(routed.plan());
    }

    @Test
    void mainThreadOnlyAndForcedRoutesNeverRunPureSearch() {
        for (var snapshot : List.of(snapshot(1, true, false), snapshot(1, false, true))) {
            assertNull(compute(snapshot, routing(snapshot, Set.of(), 512), 1).plan());
        }
    }

    @Test
    void repeatQuantityAndNewInventoryAreNotReusedFromPreviousQuery() {
        var first = snapshot(1, false, false);
        assertFalse(compute(first, routing(first, Set.of(), 512), 2).plan().feasible());
        var next = snapshot(2, false, false);
        assertTrue(compute(next, routing(next, Set.of(), 512), 2).plan().feasible());
        assertTrue(compute(first, routing(first, Set.of(), 512), 1).plan().feasible());
    }

    @Test
    void routingPolicyCopiesMutableInputsAndDetectsChanges() {
        var stock = new java.util.LinkedHashMap<MaterialRef, Integer>();
        stock.put(IRON, 1);
        var catalysts = new java.util.HashSet<ResourceLocation>();
        var routing = new AsyncPurePlanningService.RouteInputs(stock, 512, catalysts, Set.of(), Set.of());
        stock.clear();
        catalysts.add(TARGET);
        assertEquals(Map.of(IRON, 1), routing.available());
        assertTrue(routing.catalystOutputs().isEmpty());
        assertTrue(routing.matchesPolicy(512, Set.of(), Set.of(), Set.of()));
        assertFalse(routing.matchesPolicy(513, Set.of(), Set.of(), Set.of()));
        assertFalse(routing.matchesPolicy(512, catalysts, Set.of(), Set.of()));
        assertFalse(routing.matchesPolicy(512, Set.of(), Set.of(TARGET), Set.of()));
        assertFalse(routing.matchesPolicy(512, Set.of(), Set.of(), Set.of(TARGET)));
        assertThrows(UnsupportedOperationException.class, () -> routing.available().clear());
    }

    @Test
    void cancelledJobDoesNotLeaveLookupScopeOrTurnIntoMissingMaterials() {
        var snapshot = snapshot(1, false, false);
        Thread.currentThread().interrupt();
        try {
            assertThrows(java.util.concurrent.CancellationException.class,
                    () -> compute(snapshot, routing(snapshot, Set.of(), 512), 1));
        } finally {
            Thread.interrupted();
        }
        assertFalse(PlanningLookupCache.isActive());
        assertTrue(compute(snapshot, routing(snapshot, Set.of(), 512), 1).plan().feasible());
    }

    @Test
    void nodeLimitOnlyRoutesToBackgroundRatherThanRejectingTheSearch() {
        var missing = new MaterialRef(new ResourceLocation("minecraft:gold_ingot"), "");
        var intermediate = new MaterialRef(new ResourceLocation("minecraft:gold_block"), "");
        var target = new RecipeNode(TARGET, RESULT, 1, List.of(new IngredientRef(List.of(intermediate), 1)));
        var gold = new RecipeNode(new ResourceLocation("test:gold"), missing, 1,
                List.of(new IngredientRef(List.of(IRON), 1)));
        var block = new RecipeNode(new ResourceLocation("test:block"), intermediate, 1,
                List.of(new IngredientRef(List.of(missing), 1)));
        var snapshot = new PlanningSnapshot(UUID.randomUUID(), 1, 1, TARGET,
                Map.of(new StackKey(Items.IRON_INGOT, null), 1),
                Map.of(), new ImmutableRecipeGraph(Map.of(RESULT, List.of(target),
                        missing, List.of(gold), intermediate, List.of(block))), "network", "binding", false);
        var combined = compute(snapshot, routing(snapshot, Set.of(), 1), 1);
        assertEquals(PureDemandTreeInspector.Status.NODE_LIMIT, combined.inspection().status());
        assertNotNull(combined.plan());
        assertTrue(combined.plan().feasible());
    }

    @Test
    void sharedRoutedPreviewUpdatesGenerationWithoutSecondHandoff() throws Exception {
        var callbacks = new LinkedBlockingQueue<Runnable>();
        var snapshot = snapshot(1, false, false);
        var latest = new PlanningSnapshot(snapshot.playerId(), 2, snapshot.recipeRevision(), TARGET,
                snapshot.availableItems(), Map.of(), snapshot.recipeGraph(), "network", "binding", false);
        var result = new AtomicReference<AsyncPurePlanningService.RoutedPlan>();
        var failure = new AtomicReference<Throwable>();
        try (var coordinator = new AsyncPlanningCoordinator(1)) {
            var service = new AsyncPurePlanningService(coordinator);
            var routing = routing(snapshot, Set.of(), 512);
            service.submitRouted(snapshot, routing, 1, callbacks::add, 30, 1000, 100, 1500,
                    result::set, failure::set);
            Runnable handoff = callbacks.poll(5, TimeUnit.SECONDS);
            assertNotNull(handoff);
            service.submitRouted(latest, routing, 1, callbacks::add, 30, 1000, 100, 1500,
                    result::set, failure::set);
            handoff.run();
            assertNull(failure.get());
            assertEquals(2, result.get().snapshot().requestGeneration());
            assertTrue(callbacks.isEmpty());
        }
    }

    @Test
    void cancellationBeforeHandoffDoesNotPublishCompletedPlan() throws Exception {
        var callbacks = new LinkedBlockingQueue<Runnable>();
        var snapshot = snapshot(1, false, false);
        var result = new AtomicReference<AsyncPurePlanningService.RoutedPlan>();
        var failure = new AtomicReference<Throwable>();
        try (var coordinator = new AsyncPlanningCoordinator(1)) {
            new AsyncPurePlanningService(coordinator).submitRouted(snapshot, routing(snapshot, Set.of(), 512),
                    1, callbacks::add, 30, 1000, 100, 1500, result::set, failure::set);
            Runnable handoff = callbacks.poll(5, TimeUnit.SECONDS);
            assertNotNull(handoff);
            coordinator.cancel(snapshot.playerId());
            handoff.run();
            Runnable cancellation = callbacks.poll(5, TimeUnit.SECONDS);
            assertNotNull(cancellation);
            cancellation.run();
            assertNull(result.get());
            assertInstanceOf(java.util.concurrent.CancellationException.class, failure.get());
        }
    }

    @Test
    void changedRoutingPolicyCannotShareOldComputedPlan() throws Exception {
        var callbacks = new LinkedBlockingQueue<Runnable>();
        var snapshot = snapshot(0, false, false);
        var result = new AtomicReference<AsyncPurePlanningService.RoutedPlan>();
        try (var coordinator = new AsyncPlanningCoordinator(1)) {
            var service = new AsyncPurePlanningService(coordinator);
            service.submitRouted(snapshot, routing(snapshot, Set.of(), 512), 1,
                    callbacks::add, 30, 1000, 100, 1500, result::set, failure -> {});
            Runnable oldHandoff = callbacks.poll(5, TimeUnit.SECONDS);
            assertNotNull(oldHandoff);
            service.submitRouted(snapshot, routing(snapshot, Set.of(IRON.itemId()), 512), 1,
                    callbacks::add, 30, 1000, 100, 1500, result::set, failure -> fail(failure));
            oldHandoff.run();
            assertNull(result.get());
            for (int index = 0; index < 2; index++) {
                Runnable callback = callbacks.poll(5, TimeUnit.SECONDS);
                assertNotNull(callback);
                callback.run();
            }
            assertNotNull(result.get());
            assertNull(result.get().plan());
            assertEquals(PureDemandTreeInspector.Status.UNPROJECTED_DEPENDENCY,
                    result.get().inspection().status());
        }
    }

    private static AsyncPurePlanningService.RoutedPlan compute(PlanningSnapshot snapshot,
                                                                AsyncPurePlanningService.RouteInputs routing,
                                                                int repeats) {
        return PlanningThreadContext.runInBackground(() -> AsyncPurePlanningService.computeRouted(
                snapshot, routing, repeats, 30, 1000, 100, 1500));
    }

    private static AsyncPurePlanningService.RouteInputs routing(PlanningSnapshot snapshot,
                                                                Set<ResourceLocation> incompatible, int nodes) {
        return new AsyncPurePlanningService.RouteInputs(
                ImmutableRecipeGraphProjector.projectAvailability(snapshot.availableItems()),
                nodes, Set.of(), Set.of(), incompatible);
    }

    private static PlanningSnapshot snapshot(int count, boolean mainThreadOnly, boolean forced) {
        var target = new RecipeNode(TARGET, RESULT, 1, List.of(new IngredientRef(List.of(IRON), 1)));
        return new PlanningSnapshot(UUID.randomUUID(), 1, 1, TARGET,
                count > 0 ? Map.of(new StackKey(Items.IRON_INGOT, null), count) : Map.of(),
                forced ? Map.of(IRON.itemId(), TARGET) : Map.of(),
                new ImmutableRecipeGraph(Map.of(RESULT, List.of(target))), "network", "binding", mainThreadOnly);
    }
}
