package com.huanghuang.rsintegration.crafting.planning;

import com.huanghuang.rsintegration.crafting.CraftingResolver.StackKey;
import com.huanghuang.rsintegration.crafting.graph.DemandRole;
import com.huanghuang.rsintegration.crafting.plan.PlanResponse;
import com.huanghuang.rsintegration.crafting.plan.PlanStep;
import com.huanghuang.rsintegration.crafting.plan.RecipeChoice;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.IngredientRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.MaterialRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.NbtMatchMode;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.RecipeNode;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlanningRouteReuseTest extends BootstrapTest {
    private static final ResourceLocation TARGET = id("target");
    private static final MaterialRef BLOCK = material("iron_block");
    private static final MaterialRef IRON = material("iron_ingot");
    private static final MaterialRef ORE = material("iron_ore");
    private static final MaterialRef GOLD = material("gold_ingot");
    private static final RecipeNode SMELT = new RecipeNode(id("smelt"), IRON, 1,
            List.of(new IngredientRef(List.of(ORE), 1)));
    private static final RecipeNode ALTERNATE = new RecipeNode(id("alternate"), IRON, 1,
            List.of(new IngredientRef(List.of(GOLD), 1)));
    private static final RecipeNode ROOT = new RecipeNode(TARGET, BLOCK, 1,
            List.of(new IngredientRef(List.of(IRON), 1), new IngredientRef(List.of(IRON), 1)));
    private static final ImmutableRecipeGraph GRAPH = new ImmutableRecipeGraph(Map.of(
            BLOCK, List.of(ROOT), IRON, List.of(SMELT, ALTERNATE)));

    @Test
    void replenishedMissingPreviewReusesRouteAndBuildsFreshSharedMaterialFlow() {
        var before = snapshot(Map.of(new StackKey(Items.IRON_ORE, null), 1L), GRAPH);
        var missing = compute(before, null);
        assertFalse(missing.plan().feasible());
        // 实际缺料预览只有展示步骤，缓存不保留失败 Result 或可执行图。
        var steps = missing.plan().steps().stream().map(step -> new PlanStep(step.recipeId(),
                new ItemStack(Items.IRON_INGOT), step.batches(), List.of())).toList();
        var response = new PlanResponse(false, "", ItemStack.EMPTY, steps, Map.of(), List.of(), TARGET.toString());
        var cache = new PlanCache(10L);
        var key = new PlanCache.Key(before.playerId(), TARGET, Map.of(), 1, "",
                Map.of(), PlanningPreferences.empty(), before.recipeRevision());
        cache.put(key, response, before, 1L);
        cache.retainForRequest(before.playerId(), 1L, before);
        assertNull(cache.get(key, 20L));
        var hint = PlanningRouteHint.from(cache.findForReplan(key));
        assertNotNull(hint);
        var after = withStock(before, Map.of(new StackKey(Items.IRON_ORE, null), 2L));
        try (var projection = mockStatic(ImmutableRecipeGraphProjector.class, CALLS_REAL_METHODS)) {
            var replay = compute(after, hint);
            assertTrue(replay.plan().feasible());
            assertNotNull(replay.plan().materialFlow());
            assertEquals(2L, replay.plan().steps().get(0).batches());
            assertTrue(replay.plan().remaining().isEmpty());
            projection.verify(() -> ImmutableRecipeGraphProjector.restrictToDependencies(GRAPH, TARGET), never());
        }
    }

    @Test
    void insufficientStockFallsBackWithoutPublishingASuccessOrChangingInventory() {
        var before = snapshot(Map.of(new StackKey(Items.IRON_ORE, null), 2L), GRAPH);
        var hint = hint(before, compute(before, null).plan());
        var after = withStock(before, Map.of(new StackKey(Items.IRON_ORE, null), 1L));
        var replay = compute(after, hint);
        assertFalse(replay.plan().feasible());
        assertFalse(replay.plan().missing().isEmpty());
        assertEquals(compute(after, null).plan(), replay.plan());
        assertEquals(1L, after.availableItems().get(new StackKey(Items.IRON_ORE, null)));
    }

    @Test
    void newStockCanSelectAlternativeOutsideThePreviousRoute() {
        var before = snapshot(Map.of(new StackKey(Items.IRON_ORE, null), 2L), GRAPH);
        var hint = hint(before, compute(before, null).plan());
        var after = withStock(before, Map.of(new StackKey(Items.GOLD_INGOT, null), 2L));
        try (var projection = mockStatic(ImmutableRecipeGraphProjector.class, CALLS_REAL_METHODS)) {
            var replay = compute(after, hint);
            assertTrue(replay.plan().feasible());
            assertEquals(ALTERNATE.recipeId(), replay.plan().steps().get(0).recipeId());
            projection.verify(() -> ImmutableRecipeGraphProjector.restrictToDependencies(GRAPH, TARGET));
        }
    }

    @Test
    void previouslyStockedIntermediateCanExpandAfterItsStockIsRemoved() {
        var before = snapshot(Map.of(new StackKey(Items.IRON_INGOT, null), 2L), GRAPH);
        var hint = hint(before, compute(before, null).plan());
        assertEquals(1, hint.recipes().size());
        var after = withStock(before, Map.of(new StackKey(Items.IRON_ORE, null), 2L));
        var replay = compute(after, hint);
        assertTrue(replay.plan().feasible());
        assertEquals(SMELT.recipeId(), replay.plan().steps().get(0).recipeId());
    }

    @Test
    void wrongNbtCannotSatisfyPreviousRoute() {
        MaterialRef tagged = new MaterialRef(ORE.itemId(), "{grade:1}");
        var exact = new RecipeNode(SMELT.recipeId(), IRON, 1,
                List.of(new IngredientRef(List.of(tagged), 1)));
        var graph = new ImmutableRecipeGraph(Map.of(BLOCK, List.of(ROOT), IRON, List.of(exact)));
        var before = snapshot(Map.of(new StackKey(Items.IRON_ORE, "{grade:1}"), 2L), graph);
        var hint = hint(before, compute(before, null).plan());
        var after = withStock(before, Map.of(new StackKey(Items.IRON_ORE, "{grade:2}"), 2L));
        assertFalse(compute(after, hint).plan().feasible());
    }

    @Test
    void replayPreservesCatalystSemantics() {
        var catalytic = new RecipeNode(SMELT.recipeId(), IRON, 1, List.of(
                new IngredientRef(List.of(ORE), 1),
                new IngredientRef(List.of(GOLD), 1, NbtMatchMode.EXACT, DemandRole.CATALYST)));
        var graph = new ImmutableRecipeGraph(Map.of(BLOCK, List.of(ROOT), IRON, List.of(catalytic)));
        var before = snapshot(Map.of(new StackKey(Items.IRON_ORE, null), 2L,
                new StackKey(Items.GOLD_INGOT, null), 1L), graph);
        var plan = compute(before, null).plan();
        assertNotNull(plan);
        var hint = hint(before, plan);
        var replay = compute(before, hint).plan();
        assertTrue(replay.feasible());
        assertEquals(1L, replay.remaining().get(GOLD));
        assertFalse(compute(withStock(before, Map.of(new StackKey(Items.IRON_ORE, null), 2L)), hint)
                .plan().feasible());
    }

    @Test
    void incompatibleDependencyStillUsesTypedRoute() {
        var before = snapshot(Map.of(), new ImmutableRecipeGraph(Map.of(BLOCK, List.of(ROOT))));
        var hint = new PlanningRouteHint(before, List.of(ROOT));
        var routing = AsyncPurePlanningService.RouteInputs.fromItems(before.availableItems(),
                512, Set.of(), Set.of(), Set.of(IRON.itemId())).withPreviousRoute(hint);
        var replay = AsyncPurePlanningService.computeRouted(before, routing, 1, 100, 1000, 100, 1500);
        assertNull(replay.plan());
        assertEquals(PureDemandTreeInspector.Status.UNPROJECTED_DEPENDENCY, replay.inspection().status());
    }

    @Test
    void contextAndRecipeChangesInvalidateHints() {
        var before = snapshot(Map.of(), GRAPH);
        var hint = new PlanningRouteHint(before, List.of(ROOT, SMELT));
        for (int changed = 0; changed < 8; changed++) {
            var after = new PlanningSnapshot(changed == 0 ? UUID.randomUUID() : before.playerId(),
                    2, changed == 1 ? 2 : 1, TARGET, Map.of(),
                    changed == 2 ? Map.of(IRON.itemId(), ALTERNATE.recipeId()) : Map.of(), GRAPH,
                    changed == 3 ? "other:stock" : "network:new_stock",
                    changed == 4 ? "other_binding" : "binding",
                    changed == 5 ? Set.of(IRON.itemId()) : Set.of(), changed == 6,
                    changed == 7 ? new PlanningPreferences(Map.of(IRON.itemId().toString(),
                            RecipeChoice.NoRecipe.INSTANCE), Map.of(), Set.of()) : PlanningPreferences.empty());
            assertNull(hint.graphFor(after), "context change=" + changed);
        }
        var modified = new RecipeNode(SMELT.recipeId(), IRON, 1, List.of(new IngredientRef(List.of(GOLD), 3)));
        var changedGraph = new ImmutableRecipeGraph(Map.of(BLOCK, List.of(ROOT), IRON, List.of(modified)));
        var changed = new PlanningSnapshot(before.playerId(), 2, 1, TARGET, Map.of(), Map.of(),
                changedGraph, "network:new_stock", "binding", false);
        assertNull(hint.graphFor(changed));
        assertNotNull(hint.graphFor(withStock(before, Map.of())));
    }

    @Test
    void cancelledReplayDoesNotPublishResult() {
        var before = snapshot(Map.of(), GRAPH);
        try {
            Thread.currentThread().interrupt();
            assertThrows(CancellationException.class,
                    () -> compute(before, new PlanningRouteHint(before, List.of(ROOT, SMELT))));
        } finally {
            Thread.interrupted();
        }
    }

    private static PlanningRouteHint hint(PlanningSnapshot snapshot, PureRecipePlanner.Result result) {
        var response = new PlanResponse(result.feasible(), "", ItemStack.EMPTY, List.of(), Map.of(), List.of(), "test");
        return PlanningRouteHint.from(new PlanCache.Entry(response, snapshot, result, null, 0L));
    }

    private static AsyncPurePlanningService.RoutedPlan compute(PlanningSnapshot snapshot, PlanningRouteHint hint) {
        var routing = AsyncPurePlanningService.RouteInputs.fromItems(snapshot.availableItems(), 512,
                Set.of(), Set.of(), Set.of()).withPreviousRoute(hint);
        return AsyncPurePlanningService.computeRouted(snapshot, routing, 1, 100, 1000, 100, 1500);
    }

    private static PlanningSnapshot snapshot(Map<StackKey, Long> stock, ImmutableRecipeGraph graph) {
        return new PlanningSnapshot(UUID.randomUUID(), 1, 1, TARGET, stock, Map.of(), graph,
                "network:old_stock", "binding", false);
    }

    private static PlanningSnapshot withStock(PlanningSnapshot before, Map<StackKey, Long> stock) {
        return new PlanningSnapshot(before.playerId(), 2, 1, TARGET, stock, Map.of(), before.recipeGraph(),
                "network:new_stock", "binding", false);
    }

    private static ResourceLocation id(String value) { return new ResourceLocation("reuse", value); }
    private static MaterialRef material(String value) {
        return new MaterialRef(new ResourceLocation("minecraft", value), "");
    }
}
