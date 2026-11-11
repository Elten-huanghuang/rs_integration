package com.huanghuang.rsintegration.crafting.planning;

import com.huanghuang.rsintegration.util.PerformanceMonitor;
import com.huanghuang.rsintegration.crafting.CraftingResolver.StackKey;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.IngredientRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.MaterialRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.NbtMatchMode;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.RecipeNode;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

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
    void mainThreadOnlySkipsPureSearchAndOutOfScopeForcedSelectionsAreIgnored() {
        var mainThreadOnly = snapshot(1, true, false);
        assertNull(compute(mainThreadOnly, routing(mainThreadOnly, Set.of(), 512), 1).plan());

        var original = snapshot(1, false, false);
        var staleForced = new PlanningSnapshot(original.playerId(), 1, 1, TARGET,
                original.availableItems(), Map.of(id("unrelated_material"), id("old_recipe")),
                original.recipeGraph(), "network", "binding", false);
        assertNotNull(compute(staleForced, routing(staleForced, Set.of(), 512), 1).plan());
    }

    @Test
    void currentInputSelectionCannotBeDiscardedAsAnOutOfScopePreference() {
        var selected = snapshot(1, false, true);

        var routed = compute(selected, routing(selected, Set.of(), 512), 1);

        assertFalse(routed.forcedRecipesProjected());
        assertNull(routed.plan());
    }

    @Test
    void routedPlanningUsesThePreparedForcedRecipeProjectionOnce() {
        MaterialRef wood = new MaterialRef(new ResourceLocation("minecraft:oak_log"), "");
        MaterialRef planks = new MaterialRef(new ResourceLocation("minecraft:oak_planks"), "");
        RecipeNode cheap = new RecipeNode(id("cheap"), planks, 1,
                List.of(new IngredientRef(List.of(wood), 1)));
        RecipeNode forced = new RecipeNode(id("forced"), planks, 1,
                List.of(new IngredientRef(List.of(wood), 1)));
        var snapshot = new PlanningSnapshot(UUID.randomUUID(), 1, 1, id("forced"),
                Map.of(new StackKey(Items.OAK_LOG, null), 1L),
                Map.of(ForcedRecipeGraphProjector.preferenceKey(planks), id("forced")),
                new ImmutableRecipeGraph(Map.of(planks, List.of(cheap, forced))),
                "network", "binding", false);

        var routed = compute(snapshot, routing(snapshot, Set.of(), 512), 1);

        assertNotNull(routed.plan());
        assertTrue(routed.plan().feasible());
        assertTrue(routed.forcedRecipesProjected());
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
        var stock = new java.util.LinkedHashMap<MaterialRef, Long>();
        stock.put(IRON, 1L);
        var catalysts = new java.util.HashSet<ResourceLocation>();
        var routing = new AsyncPurePlanningService.RouteInputs(stock, 512, catalysts, Set.of(), Set.of());
        stock.clear();
        catalysts.add(TARGET);
        assertEquals(Map.of(IRON, 1L), routing.available());
        assertTrue(routing.catalystOutputs().isEmpty());
        assertTrue(routing.matchesPolicy(512, Set.of(), Set.of(), Set.of()));
        assertFalse(routing.matchesPolicy(513, Set.of(), Set.of(), Set.of()));
        assertFalse(routing.matchesPolicy(512, catalysts, Set.of(), Set.of()));
        assertFalse(routing.matchesPolicy(512, Set.of(), Set.of(TARGET), Set.of()));
        assertFalse(routing.matchesPolicy(512, Set.of(), Set.of(), Set.of(TARGET)));
        assertThrows(UnsupportedOperationException.class, () -> routing.available().clear());
    }

    @Test
    void sameSnapshotReusesTheOriginalProjectionAndKeepsItsValueSemantics() {
        var snapshot = snapshot(2, false, false);
        var routing = routing(snapshot, Set.of(), 512);
        var legacy = new AsyncPurePlanningService.RouteInputs(
                ImmutableRecipeGraphProjector.projectAvailability(snapshot.availableItems()),
                512, Set.of(), Set.of(), Set.of());

        assertSame(routing.available(), routing.projectedAvailabilityFor(snapshot));
        assertNull(legacy.projectedAvailabilityFor(snapshot));
        assertEquals(legacy, routing);
        assertEquals(legacy.hashCode(), routing.hashCode());
        assertEquals(legacy.toString(), routing.toString());
        try (var projection = mockStatic(ImmutableRecipeGraphProjector.class, CALLS_REAL_METHODS)) {
            var expected = compute(snapshot, legacy, 2);
            projection.verify(() -> ImmutableRecipeGraphProjector.projectAvailability(
                    snapshot.availableItems()), times(1));
            projection.clearInvocations();
            assertEquals(expected, compute(snapshot, routing, 2));
            projection.verify(() -> ImmutableRecipeGraphProjector.projectAvailability(
                    snapshot.availableItems()), never());
        }
        assertEquals(Map.of(IRON, 2L), routing.available());
        assertThrows(UnsupportedOperationException.class, () -> routing.available().clear());
    }

    @Test
    void immutableExecutionSourceCanBeProjectedBeforeTheSnapshotIsCaptured() {
        var original = snapshot(1, false, false);
        var routing = AsyncPurePlanningService.RouteInputs.fromItems(
                original.availableItems(), 512, Set.of(), Set.of(), Set.of());
        var captured = new PlanningSnapshot(original.playerId(), 2, 1, TARGET,
                original.availableItems(), Map.of(), original.recipeGraph(), "network", "binding", false);

        assertSame(original.availableItems(), captured.availableItems());
        assertSame(routing.available(), routing.projectedAvailabilityFor(captured));
        assertTrue(compute(captured, routing, 1).plan().feasible());
        assertEquals(Map.of(IRON, 1L), routing.available());
    }

    @Test
    void differentRoutingStockNeverReplacesTheSearchSnapshotOrItsMissingMaterials() {
        for (int searchCount : List.of(0, 1)) {
            var snapshot = snapshot(searchCount, false, false);
            var routeSource = snapshot(1 - searchCount, false, false);
            var routing = routing(routeSource, Set.of(), 512);
            var legacy = new AsyncPurePlanningService.RouteInputs(
                    new LinkedHashMap<>(routing.available()), 512, Set.of(), Set.of(), Set.of());

            assertNull(routing.projectedAvailabilityFor(snapshot));
            var actual = compute(snapshot, routing, 1);
            assertEquals(compute(snapshot, legacy, 1), actual);
            assertNotNull(actual.plan());
            assertEquals(searchCount == 1, actual.plan().feasible());
            assertEquals(searchCount == 1, actual.plan().missing().isEmpty());
        }
    }

    @Test
    void mutableOrEqualButDistinctSourcesCannotEnableProjectionReuse() {
        var original = snapshot(1, false, false);
        var source = new HashMap<>(original.availableItems());
        var routing = AsyncPurePlanningService.RouteInputs.fromItems(
                source, 512, Set.of(), Set.of(), Set.of());
        var snapshot = new PlanningSnapshot(original.playerId(), 1, 1, TARGET, source,
                Map.of(), original.recipeGraph(), "network", "binding", false);

        assertEquals(source, snapshot.availableItems());
        assertNotSame(source, snapshot.availableItems());
        assertNull(routing.projectedAvailabilityFor(snapshot));
        source.clear();
        assertEquals(Map.of(IRON, 1L), routing.available());
        var actual = compute(snapshot, routing, 2);
        assertNotNull(actual.plan());
        assertFalse(actual.plan().feasible());
        assertFalse(actual.plan().missing().isEmpty());
        assertEquals(1L, snapshot.availableItems().get(new StackKey(Items.IRON_INGOT, null)));
    }

    @Test
    void nbtHashCollisionsKeepVariantOrderAndIndependentLedgers() {
        MaterialRef first = new MaterialRef(IRON.itemId(), "{variant:\"Aa\"}");
        MaterialRef second = new MaterialRef(IRON.itemId(), "{variant:\"BB\"}");
        assertNotEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
        RecipeNode target = new RecipeNode(TARGET, RESULT, 1, List.of(
                new IngredientRef(List.of(IRON), 1, NbtMatchMode.ANY),
                new IngredientRef(List.of(first), 1)));
        var items = new LinkedHashMap<StackKey, Long>();
        items.put(new StackKey(Items.IRON_INGOT, second.nbt()), 1L);
        items.put(new StackKey(Items.IRON_INGOT, first.nbt()), 1L);
        items.put(new StackKey(Items.DIRT, null), 0L);
        items.put(new StackKey(Items.STONE, null), Long.MAX_VALUE);
        var snapshot = new PlanningSnapshot(UUID.randomUUID(), 1, 1, TARGET, items,
                Map.of(), new ImmutableRecipeGraph(Map.of(RESULT, List.of(target))),
                "network", "binding", false);
        var routing = routing(snapshot, Set.of(), 512);
        var legacy = new AsyncPurePlanningService.RouteInputs(
                ImmutableRecipeGraphProjector.projectAvailability(snapshot.availableItems()),
                512, Set.of(), Set.of(), Set.of());
        var order = List.copyOf(legacy.available().entrySet());

        assertSame(routing.available(), routing.projectedAvailabilityFor(snapshot));
        assertEquals(order, List.copyOf(routing.available().entrySet()));
        for (int repeats : List.of(1, 2)) {
            var actual = compute(snapshot, routing, repeats);
            assertEquals(compute(snapshot, legacy, repeats), actual);
            assertNotNull(actual.plan());
            assertEquals(repeats == 1, actual.plan().feasible());
            assertEquals(order, List.copyOf(routing.available().entrySet()));
        }
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
                Map.of(new StackKey(Items.IRON_INGOT, null), 1L),
                Map.of(), new ImmutableRecipeGraph(Map.of(RESULT, List.of(target),
                        missing, List.of(gold), intermediate, List.of(block))), "network", "binding", false);
        var combined = compute(snapshot, routing(snapshot, Set.of(), 1), 1);
        assertEquals(PureDemandTreeInspector.Status.NODE_LIMIT, combined.inspection().status());
        assertNotNull(combined.plan());
        assertTrue(combined.plan().feasible());
    }

    @Test
    void separateRoutedRequestsKeepTheirOwnHandoffs() throws Exception {
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
            var legacy = new AsyncPurePlanningService.RouteInputs(
                    new LinkedHashMap<>(routing.available()), 512, Set.of(), Set.of(), Set.of());
            assertNull(legacy.projectedAvailabilityFor(latest));
            service.submitRouted(latest, legacy, 1, callbacks::add, 30, 1000, 100, 1500,
                    result::set, failure::set);
            handoff.run();
            assertNull(failure.get());
            assertEquals(1, result.get().snapshot().requestGeneration());
            Runnable secondHandoff = callbacks.poll(5, TimeUnit.SECONDS);
            assertNotNull(secondHandoff);
            secondHandoff.run();
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

    @Test
    void pureShortageDoesNotHideTypedAlternativeBehindAnExistingProducer() {
        var snapshot = mixedProducerSnapshot(false);
        var routed = compute(snapshot, routing(snapshot, Set.of(IRON.itemId()), 512), 1);
        assertEquals(PureDemandTreeInspector.Status.UNPROJECTED_DEPENDENCY, routed.inspection().status());
        assertNull(routed.plan(), "普通配方缺料并不证明未投影的机器配方也缺料");
        assertEquals(IRON, routed.inspection().unresolved());
    }

    @Test
    void successfulPureRouteDoesNotFallBackForUnusedTypedAlternatives() {
        var snapshot = mixedProducerSnapshot(true);
        var routed = compute(snapshot, routing(snapshot, Set.of(IRON.itemId()), 512), 1);
        assertTrue(routed.plan().feasible());
    }

    @Test
    void unrelatedUnprojectedOutputDoesNotForceTypedPlanning() {
        var snapshot = mixedProducerSnapshot(false);
        var routed = compute(snapshot, routing(snapshot, Set.of(id("unrelated")), 512), 1);
        assertNotNull(routed.plan());
        assertFalse(routed.plan().feasible());
    }

    @Test
    void explicitProjectedRecipeDoesNotFallBackToAnUnselectedProducer() {
        var base = mixedProducerSnapshot(false);
        var snapshot = new PlanningSnapshot(base.playerId(), 1, 1, TARGET, base.availableItems(),
                Map.of(IRON.itemId(), id("smelting")), base.recipeGraph(), "network", "binding", false);
        var routed = compute(snapshot, routing(snapshot, Set.of(IRON.itemId()), 512), 1);
        assertNotNull(routed.plan());
        assertFalse(routed.plan().feasible());
    }

    private static PlanningSnapshot mixedProducerSnapshot(boolean stocked) {
        var ore = new MaterialRef(new ResourceLocation("minecraft:iron_ore"), "");
        var other = new MaterialRef(new ResourceLocation("minecraft:echo_shard"), "");
        var target = new RecipeNode(TARGET, RESULT, 1, List.of(
                new IngredientRef(List.of(other), 1), new IngredientRef(List.of(IRON), 2)));
        var smelting = new RecipeNode(id("smelting"), IRON, 1, List.of(new IngredientRef(List.of(ore), 1)));
        return new PlanningSnapshot(UUID.randomUUID(), 1, 1, TARGET,
                stocked ? Map.of(new StackKey(Items.IRON_ORE, null), 2L,
                        new StackKey(Items.ECHO_SHARD, null), 1L) : Map.of(), Map.of(),
                new ImmutableRecipeGraph(Map.of(RESULT, List.of(target), IRON, List.of(smelting))),
                "network", "binding", false);
    }

    private static AsyncPurePlanningService.RoutedPlan compute(PlanningSnapshot snapshot,
                                                                AsyncPurePlanningService.RouteInputs routing,
                                                                int repeats) {
        return PlanningThreadContext.runInBackground(() -> AsyncPurePlanningService.computeRouted(
                snapshot, routing, repeats, 30, 1000, 100, 1500));
    }

    private static AsyncPurePlanningService.RouteInputs routing(PlanningSnapshot snapshot,
                                                                Set<ResourceLocation> incompatible, int nodes) {
        return AsyncPurePlanningService.RouteInputs.fromItems(
                snapshot.availableItems(),
                nodes, Set.of(), Set.of(), incompatible);
    }

    private static PlanningSnapshot snapshot(int count, boolean mainThreadOnly, boolean forced) {
        var target = new RecipeNode(TARGET, RESULT, 1, List.of(new IngredientRef(List.of(IRON), 1)));
        return new PlanningSnapshot(UUID.randomUUID(), 1, 1, TARGET,
                count > 0 ? Map.of(new StackKey(Items.IRON_INGOT, null), (long) count) : Map.of(),
                forced ? Map.of(IRON.itemId(), TARGET) : Map.of(),
                new ImmutableRecipeGraph(Map.of(RESULT, List.of(target))), "network", "binding", mainThreadOnly);
    }

    private static ResourceLocation id(String path) {
        return new ResourceLocation("test", path);
    }
}
