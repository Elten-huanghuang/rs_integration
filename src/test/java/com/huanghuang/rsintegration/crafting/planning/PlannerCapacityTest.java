package com.huanghuang.rsintegration.crafting.planning;

import com.huanghuang.rsintegration.crafting.graph.DemandRole;
import com.huanghuang.rsintegration.crafting.plan.RecipeChoice;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.IngredientRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.MaterialRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.NbtMatchMode;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.RecipeNode;
import com.huanghuang.rsintegration.crafting.planning.PureRecipePlanner.MaterialUse;
import com.huanghuang.rsintegration.crafting.planning.PureRecipePlanner.Production;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class PlannerCapacityTest extends BootstrapTest {
    @Test
    void transitiveMissingRoutesAndCraftedAuxiliaryKeepSparseGraphsCompact() {
        for (int levels : new int[] {10, 100, 200, 500}) {
            for (boolean crafted : new boolean[] {false, true}) {
                Scenario original = reviewedLayers(levels, true, true, false);
                Map<MaterialRef, List<RecipeNode>> recipes = new LinkedHashMap<>(original.graph().recipesByOutput());
                Map<MaterialRef, Long> stock = new LinkedHashMap<>(original.stock());
                if (crafted) {
                    recipes.put(material("fuel"), List.of(recipe("fuel", material("fuel"), 16L,
                            need(material("fuel_raw"), 1L))));
                    long fuel = stock.remove(material("fuel"));
                    stock.put(material("fuel_raw"), fuel / 16L + (fuel % 16L == 0 ? 0 : 1));
                } else {
                    recipes.put(material("absent"), List.of(recipe("absent", material("absent"), 1L,
                            need(material("missing_raw"), 1L))));
                }
                Scenario scenario = new Scenario(new ImmutableRecipeGraph(recipes), stock, original.roots());
                var result = PureRecipePlanner.resolve(scenario.graph(), stock, scenario.roots(), 4096, 65_536, 8192);
                assertEquals(PureRecipePlanner.Status.SUCCESS, result.status());
                assertEquals(2 * levels + (crafted ? 2 : 1), result.steps().size());
                assertEquals(0, result.backtracks());
                if (crafted) {
                    long fuelUse = result.steps().stream().filter(step -> !step.recipeId().equals(id("fuel"))
                                    && !step.recipeId().equals(id("root")))
                            .mapToLong(PureRecipePlanner.PlannedStep::batches).sum();
                    assertEquals(1, result.steps().stream().filter(step -> step.recipeId().equals(id("fuel"))).count());
                    long batches = result.steps().stream().filter(step -> step.recipeId().equals(id("fuel")))
                            .mapToLong(PureRecipePlanner.PlannedStep::batches).sum();
                    assertEquals((fuelUse + 15L) / 16L, batches);
                    assertEquals(batches * 16L - fuelUse, result.remaining().getOrDefault(material("fuel"), 0L));
                }
                assertFlow(scenario, result);
            }
        }
    }

    @Test
    void craftedCommonSupplyAndDistinctRoutesCanRollBackAfterConsumersFinish() {
        MaterialRef fuel = material("fuel");
        MaterialRef raw = material("raw");
        MaterialRef rare = material("rare");
        MaterialRef common = material("common");
        MaterialRef partA = material("part_a");
        MaterialRef partB = material("part_b");
        MaterialRef shared = material("shared");
        MaterialRef left = material("left");
        MaterialRef right = material("right");
        var graph = new ImmutableRecipeGraph(Map.of(
                fuel, List.of(recipe("fuel", fuel, 16L, need(raw, 1L))),
                partA, List.of(recipe("part_a", partA, 1L, need(rare, 1L))),
                partB, List.of(recipe("part_b", partB, 1L, need(common, 1L))),
                shared, List.of(recipe("a_shared", shared, 3L, need(fuel, 1L), need(partA, 1L)),
                        recipe("b_shared", shared, 2L, need(fuel, 1L), need(partB, 1L))),
                left, List.of(recipe("left", left, 1L, need(shared, 1L))),
                right, List.of(recipe("right", right, 1L, need(shared, 1L)))));
        Scenario scenario = new Scenario(graph, Map.of(raw, 1L, rare, 1L, common, 1L),
                List.of(need(left, 1L), need(right, 1L), new IngredientRef(List.of(rare), 1L, NbtMatchMode.PARTIAL)));
        var result = plan(scenario, 5);
        assertEquals(5, result.steps().size());
        assertTrue(result.backtracks() > 0);
        assertFalse(result.steps().stream().anyMatch(step -> step.recipeId().equals(id("a_shared"))));
        assertEquals(Map.of(fuel, 15L), result.remaining());
        assertFlow(scenario, result);
    }

    @Test
    void transitiveHintsIncludeAlternativeProducersCoProductsAndSeededCycles() {
        MaterialRef raw = material("raw");
        MaterialRef middle = material("middle");
        MaterialRef side = material("side");
        MaterialRef joint = material("joint");
        MaterialRef cycleA = material("cycle_a");
        MaterialRef cycleB = material("cycle_b");
        RecipeNode missing = recipe("missing", middle, 1L, need(material("missing_raw"), 1L));
        RecipeNode alternative = recipe("alternative", middle, 1L, need(raw, 1L));
        RecipeNode coProduct = withSide(recipe("joint", joint, 1L, need(middle, 1L)), side);
        RecipeNode usesSide = recipe("cycle_a", cycleA, 1L, need(cycleB, 1L), need(side, 1L));
        RecipeNode returnsSeed = recipe("cycle_b", cycleB, 2L, need(cycleA, 1L));
        var graph = new ImmutableRecipeGraph(Map.of(middle, List.of(missing, alternative), joint, List.of(coProduct),
                cycleA, List.of(usesSide), cycleB, List.of(returnsSeed)));
        var withoutSeed = new PureRecipePlanner.SeededReachability(graph, Map.of(raw, 1L), () -> {});
        assertFalse(withoutSeed.canReach(missing));
        assertTrue(withoutSeed.canReach(alternative));
        assertTrue(withoutSeed.canReach(coProduct));
        assertFalse(withoutSeed.canReach(usesSide));
        var seeded = new PureRecipePlanner.SeededReachability(graph, Map.of(raw, 1L, cycleB, 1L), () -> {});
        assertTrue(seeded.canReach(usesSide));
        assertTrue(seeded.canReach(returnsSeed));
        // 同结构换库存后重新评估，上一份可达提示不能污染新请求。
        var empty = new PureRecipePlanner.SeededReachability(graph, Map.of(cycleB, 1L), () -> {});
        assertFalse(empty.canReach(alternative));
        assertFalse(empty.canReach(usesSide));
        AtomicInteger work = new AtomicInteger();
        assertThrows(CancellationException.class, () -> new PureRecipePlanner.SeededReachability(graph,
                Map.of(raw, 1L), () -> { if (work.incrementAndGet() == 10) throw new CancellationException(); }));
        assertEquals(10, work.get());
    }

    @Test
    void craftedAuxiliaryWithDistinctProductionInputsStillSplitsSixAndFour() {
        MaterialRef fuel = material("fuel");
        MaterialRef raw = material("raw");
        MaterialRef rare = material("rare");
        MaterialRef common = material("common");
        MaterialRef partA = material("part_a");
        MaterialRef partB = material("part_b");
        MaterialRef shared = material("shared");
        MaterialRef left = material("left");
        MaterialRef right = material("right");
        var graph = new ImmutableRecipeGraph(Map.of(
                fuel, List.of(recipe("fuel", fuel, 16L, need(raw, 1L))),
                partA, List.of(recipe("part_a", partA, 1L, need(rare, 1L))),
                partB, List.of(recipe("part_b", partB, 1L, need(common, 1L))),
                shared, List.of(recipe("a_shared", shared, 1L, need(fuel, 1L), need(partA, 1L)),
                        recipe("b_shared", shared, 1L, need(fuel, 1L), need(partB, 1L))),
                left, List.of(recipe("left", left, 1L, need(shared, 5L))),
                right, List.of(recipe("right", right, 1L, need(shared, 5L)))));
        Scenario scenario = new Scenario(graph, Map.of(raw, 1L, rare, 6L, common, 4L),
                List.of(need(left, 1L), need(right, 1L)));
        var result = plan(scenario, 20);
        assertEquals(6L, result.steps().stream().filter(step -> step.recipeId().equals(id("a_shared")))
                .mapToLong(PureRecipePlanner.PlannedStep::batches).sum());
        assertEquals(4L, result.steps().stream().filter(step -> step.recipeId().equals(id("b_shared")))
                .mapToLong(PureRecipePlanner.PlannedStep::batches).sum());
        assertEquals(Map.of(fuel, 6L), result.remaining());
        assertFlow(scenario, result);
    }

    @Test
    void differentYieldSharedLayersMixFiniteStockWithoutEnumeratingRouteCombinations() {
        for (int levels : new int[] {10, 50, 100, 200, 500, 1000}) {
            Scenario scenario = reviewedLayers(levels, false, false, true);
            var result = PureRecipePlanner.resolve(scenario.graph(), scenario.stock(), scenario.roots(),
                    4096, 65_536, 8192);
            assertEquals(PureRecipePlanner.Status.SUCCESS, result.status());
            assertEquals(2 * levels + 1, result.steps().size());
            assertEquals(8 * levels + 2, result.materialFlow().events().size());
            assertTrue(result.expandedStates() < 60 * levels);
            assertEquals(0L, result.remaining().getOrDefault(material("token_a"), 0L));
            assertEquals(0L, result.remaining().getOrDefault(material("token_b"), 0L));
            assertFlow(scenario, result);
        }
    }

    @Test
    void oneStepCraftedRouteInputsDoNotConsumeTheSharedAggregationBudget() {
        for (int levels : new int[] {10, 100, 1000}) {
            for (boolean split : new boolean[] {false, true}) {
                Scenario scenario = craftedRouteTokens(levels, split);
                var result = PureRecipePlanner.resolve(scenario.graph(), scenario.stock(), scenario.roots(),
                        4096, 65_536, 8192);
                assertEquals(PureRecipePlanner.Status.SUCCESS, result.status(),
                        "levels=" + levels + ", split=" + split);
                assertTrue(result.expandedStates() < 65_536,
                        "路线提示不能耗尽普通搜索额度；levels=" + levels + ", split=" + split);
                assertTrue(result.steps().size() <= 4 * levels + 1,
                        "步骤记录应维持在构图规模内；levels=" + levels + ", split=" + split);
                assertFlow(scenario, result);
            }
        }
    }

    @Test
    void stockedCommonAuxiliaryDoesNotHideSparseSharedProduction() {
        for (int levels : new int[] {20, 50, 100, 200, 500}) {
            for (boolean auxiliary : new boolean[] {false, true}) {
                Scenario scenario = reviewedLayers(levels, true, auxiliary, false);
                var result = PureRecipePlanner.resolve(scenario.graph(), scenario.stock(), scenario.roots(),
                        4096, 65_536, 8192);
                assertEquals(PureRecipePlanner.Status.SUCCESS, result.status());
                assertEquals(2 * levels + 1, result.steps().size());
                assertTrue(result.expandedStates() < 40 * levels);
                assertFlow(scenario, result);
            }
        }
    }

    @Test
    void unsuccessfulRouteProbesLeaveBudgetForOrdinaryInventoryAllocation() {
        MaterialRef rare = material("rare");
        MaterialRef common = material("common");
        MaterialRef rareIntermediate = material("rare_intermediate");
        MaterialRef rareRaw = material("rare_raw");
        Map<MaterialRef, List<RecipeNode>> recipes = new LinkedHashMap<>();
        // 两步供料确保走深层路线探测，避免被纯库存或一步供料的快速路径跳过。
        recipes.put(rare, List.of(recipe("rare", rare, 1L, need(rareIntermediate, 1L))));
        recipes.put(rareIntermediate, List.of(recipe("rare_intermediate", rareIntermediate, 1L, need(rareRaw, 1L))));
        List<IngredientRef> roots = new ArrayList<>();
        for (int index = 0; index < 20; index++) {
            MaterialRef output = material("choice_" + index);
            recipes.put(output, List.of(recipe("a_" + index, output, 2L, need(rare, 1L)),
                    recipe("b_" + index, output, 2L, need(common, 1L))));
            roots.add(need(output, 2L));
        }
        Scenario scenario = new Scenario(new ImmutableRecipeGraph(recipes), Map.of(rareRaw, 10L, common, 10L), roots);
        for (int budget : new int[] {4096, 65_536}) {
            var result = PureRecipePlanner.resolve(scenario.graph(), scenario.stock(), scenario.roots(), 64, budget, 8192);
            assertEquals(PureRecipePlanner.Status.SUCCESS, result.status());
            assertEquals(20L, result.steps().stream().filter(step ->
                    step.recipeId().getPath().startsWith("a_") || step.recipeId().getPath().startsWith("b_")).count());
            assertTrue(result.expandedStates() >= budget / 2, "必须实际用完探测份额并计入工作量");
            assertTrue(result.expandedStates() < budget);
            assertTrue(result.backtracks() > 0);
            assertTrue(result.remaining().isEmpty());
            assertFlow(scenario, result);
        }
    }

    @Test
    void commonInputLowerBoundStillSuppliesExtraBatchesAndPreservesSurplus() {
        MaterialRef raw = material("raw");
        MaterialRef upstream = material("upstream");
        MaterialRef output = material("output");
        MaterialRef tokenA = material("token_a");
        MaterialRef tokenB = material("token_b");
        MaterialRef left = material("left");
        MaterialRef right = material("right");
        var graph = new ImmutableRecipeGraph(Map.of(
                upstream, List.of(recipe("upstream", upstream, 2L, need(raw, 1L))),
                output, List.of(recipe("a_output", output, 2L, need(upstream, 1L), need(tokenA, 1L)),
                        recipe("b_output", output, 3L, need(upstream, 1L), need(tokenB, 1L))),
                left, List.of(recipe("left", left, 1L, need(output, 3L))),
                right, List.of(recipe("right", right, 1L, need(output, 2L)))));
        // 同一结构分别测试全用低产量路线、多路线分摊，以及先消费已有产物。
        for (var stock : List.of(Map.of(raw, 2L, tokenA, 3L), Map.of(raw, 1L, tokenA, 1L, tokenB, 1L),
                Map.of(raw, 1L, tokenA, 2L, output, 1L))) {
            Scenario scenario = new Scenario(graph, stock, List.of(need(left, 1L), need(right, 1L)));
            var result = plan(scenario, 10);
            assertFlow(scenario, result);
            long lowBatches = result.steps().stream().filter(step -> step.recipeId().equals(id("a_output")))
                    .mapToLong(PureRecipePlanner.PlannedStep::batches).sum();
            assertEquals(stock.get(tokenA).longValue(), lowBatches);
            assertEquals(stock.containsKey(tokenB) ? 1L : 0L, result.steps().stream()
                    .filter(step -> step.recipeId().equals(id("b_output")))
                    .mapToLong(PureRecipePlanner.PlannedStep::batches).sum());
            assertEquals(stock.get(tokenA) == 3L ? Map.of(upstream, 1L, output, 1L) : Map.of(), result.remaining());
        }
    }

    @Test
    void differentYieldCommonSupplyRollsBackAfterLaterScarceMaterialConflict() {
        MaterialRef raw = material("raw");
        MaterialRef upstream = material("upstream");
        MaterialRef shared = material("shared");
        MaterialRef rare = material("rare");
        MaterialRef common = material("common");
        MaterialRef left = material("left");
        MaterialRef right = material("right");
        var graph = new ImmutableRecipeGraph(Map.of(
                upstream, List.of(recipe("upstream", upstream, 2L, need(raw, 1L))),
                shared, List.of(recipe("a_shared", shared, 3L, need(upstream, 1L), need(rare, 1L)),
                        recipe("b_shared", shared, 2L, need(upstream, 1L), need(common, 1L))),
                left, List.of(recipe("left", left, 1L, need(shared, 1L))),
                right, List.of(recipe("right", right, 1L, need(shared, 1L)))));
        Scenario scenario = new Scenario(graph, Map.of(raw, 1L, rare, 1L, common, 1L),
                List.of(need(left, 1L), need(right, 1L), new IngredientRef(List.of(rare), 1L, NbtMatchMode.PARTIAL)));
        var result = plan(scenario, 4);
        assertEquals(4, result.steps().size());
        assertTrue(result.backtracks() > 0);
        assertFalse(result.steps().stream().anyMatch(step -> step.recipeId().equals(id("a_shared"))));
        assertEquals(Map.of(upstream, 1L), result.remaining());
        assertFlow(scenario, result);
    }

    private static Scenario reviewedLayers(int levels, boolean sparse, boolean auxiliary, boolean split) {
        Map<MaterialRef, List<RecipeNode>> recipes = new LinkedHashMap<>();
        MaterialRef raw = material("raw");
        MaterialRef fuel = material("fuel");
        MaterialRef left = raw;
        MaterialRef right = raw;
        for (int level = 1; level <= levels; level++) {
            MaterialRef nextLeft = material("left_" + level);
            MaterialRef nextRight = material("right_" + level);
            for (MaterialRef output : List.of(nextLeft, nextRight)) {
                List<IngredientRef> first = sparse && level % 10 != 0
                        ? new ArrayList<>(List.of(need(output.equals(nextLeft) ? left : right, 1L)))
                        : new ArrayList<>(List.of(need(left, 1L), need(right, 1L)));
                List<IngredientRef> second = sparse
                        ? new ArrayList<>(List.of(need(material("absent"), 1L))) : new ArrayList<>(first);
                if (sparse) {
                    if (auxiliary) { first.add(need(fuel, 1L)); second.add(need(fuel, 1L)); }
                } else {
                    first.add(need(material("token_a"), 1L));
                    second.add(need(material("token_b"), 1L));
                }
                String name = output.itemId().getPath();
                recipes.put(output, List.of(new RecipeNode(id(name + "_a"), output, sparse ? 1L : 2L, first),
                        new RecipeNode(id(name + "_b"), output, sparse ? 1L : 3L, second)));
            }
            left = nextLeft;
            right = nextRight;
        }
        MaterialRef root = material("root");
        recipes.put(root, List.of(recipe("root", root, 1L, need(left, 1L), need(right, 1L))));
        long quantity = sparse ? 1L << (levels / 10 + 1) : 4L;
        return new Scenario(new ImmutableRecipeGraph(recipes),
                sparse ? Map.of(raw, quantity, fuel, quantity * Math.max(4L, levels * 2L))
                        : Map.of(raw, quantity, material("token_a"), split ? levels : levels * 4L,
                                material("token_b"), split ? levels : levels * 4L), List.of(need(root, 1L)));
    }

    private static Scenario craftedRouteTokens(int levels, boolean split) {
        Scenario original = reviewedLayers(levels, false, false, split);
        Map<MaterialRef, List<RecipeNode>> recipes = new LinkedHashMap<>(original.graph().recipesByOutput());
        Map<MaterialRef, Long> stock = new LinkedHashMap<>(original.stock());
        for (String side : List.of("a", "b")) {
            MaterialRef token = material("token_" + side);
            MaterialRef rawToken = material("token_raw_" + side);
            recipes.put(token, List.of(recipe("make_token_" + side, token, 1L, need(rawToken, 1L))));
            stock.put(rawToken, stock.remove(token));
        }
        return new Scenario(new ImmutableRecipeGraph(recipes), stock, original.roots());
    }

    @Test
    void allSharedProducersCanBeAmbiguousIncludingRequestedRoots() {
        Scenario original = nonEquivalentLayers(14, true, true);
        Map<MaterialRef, List<RecipeNode>> recipes = new LinkedHashMap<>(original.graph().recipesByOutput());
        recipes.remove(material("root"));
        Scenario scenario = new Scenario(new ImmutableRecipeGraph(recipes), original.stock(),
                List.of(need(material("left_14"), 1L), need(material("right_14"), 1L)));
        var result = PureRecipePlanner.resolve(scenario.graph(), scenario.stock(), scenario.roots(),
                65_536, 65_536, 8192);
        assertEquals(PureRecipePlanner.Status.SUCCESS, result.status());
        assertTrue(result.steps().size() <= 56);
        assertTrue(result.expandedStates() < 2_100);
        assertTrue(result.remaining().isEmpty());
        assertFlow(scenario, result);
    }

    @Test
    void sharedRouteCompilationHonorsCancellationAndSearchBudget() {
        Scenario scenario = nonEquivalentLayers(14, false, false);
        var limited = PureRecipePlanner.resolve(scenario.graph(), scenario.stock(), scenario.roots(), 65_536, 50, 8192);
        assertEquals(PureRecipePlanner.Status.SEARCH_LIMIT, limited.status());
        assertEquals(scenario.stock(), limited.remaining());
        assertTrue(limited.steps().isEmpty());
        var cache = new DeterministicRecipePlanner.CompilationCache(
                scenario.graph(), Map.of(), PlanningPreferences.empty(), 65_536);
            var routes = cache.routes(material -> scenario.graph().recipesByOutput().getOrDefault(material, List.of()),
                    budgetCheck -> new PureRecipePlanner.SeededReachability(
                            scenario.graph(), scenario.stock(), budgetCheck));
        AtomicInteger visited = new AtomicInteger();
        try {
            assertThrows(CancellationException.class, () -> routes.prepare(scenario.stock(), scenario.roots(),
                    Set.of(), 65_536, 65_536, () -> {
                        if (visited.incrementAndGet() == 30) Thread.currentThread().interrupt();
                        PlanningThreadContext.throwIfCancelled();
                    }));
            assertEquals(30, visited.get());
        } finally {
            Thread.interrupted();
        }
        // 取消发生在新路线编译过程中；下次求解不能继承半成品库存或选择。
        assertFlow(scenario, plan(scenario, 29));
    }

    @Test
    void branchLocalRoutesCanChangeAfterAnAggregatedPlanHasCompleted() {
        MaterialRef shared = material("shared");
        MaterialRef rare = material("rare");
        MaterialRef common = material("common");
        MaterialRef left = material("left");
        MaterialRef right = material("right");
        var graph = new ImmutableRecipeGraph(Map.of(
                shared, List.of(recipe("a_shared", shared, 3L, need(rare, 1L)),
                        recipe("b_shared", shared, 2L, need(common, 1L))),
                left, List.of(recipe("left", left, 1L, need(shared, 1L))),
                right, List.of(recipe("right", right, 1L, need(shared, 1L)))));
        // PARTIAL 将后续需求留在聚合段之外，必须撤销整个成功段后换一组选路。
        Scenario scenario = new Scenario(graph, Map.of(rare, 1L, common, 1L),
                List.of(need(left, 1L), need(right, 1L), new IngredientRef(List.of(rare), 1L, NbtMatchMode.PARTIAL)));
        var result = plan(scenario, 3);
        assertEquals(3, result.steps().size());
        assertEquals(id("b_shared"), result.steps().get(0).recipeId());
        assertTrue(result.backtracks() > 0);
        assertTrue(result.remaining().isEmpty());
        assertFlow(scenario, result);
    }

    @Test
    void nonEquivalentRoutesAcrossEverySharedLayerStayCompact() {
        for (int levels : new int[] {10, 12, 14, 20}) {
            for (boolean bothUsable : new boolean[] {false, true}) {
                Scenario scenario = nonEquivalentLayers(levels, bothUsable, false);
                var result = PureRecipePlanner.resolve(scenario.graph(), scenario.stock(), scenario.roots(),
                        65_536, 65_536, 8192);
                assertEquals(PureRecipePlanner.Status.SUCCESS, result.status(), result.toString());
                assertEquals(2 * levels + 1, result.steps().size());
                assertEquals(4 * levels + 1, scenario.graph().recipesById().size());
                assertTrue(result.expandedStates() < 150 * levels, result.toString());
                assertEquals(0, result.backtracks());
                assertFlow(scenario, result);
            }
        }
    }

    @Test
    void manyNonEquivalentLayersMixLimitedRoutesWithoutReexpandingSharedInputs() {
        for (int levels : new int[] {10, 12, 14}) {
            Scenario scenario = nonEquivalentLayers(levels, true, true);
            var result = PureRecipePlanner.resolve(scenario.graph(), scenario.stock(), scenario.roots(),
                    65_536, 65_536, 8192);
            assertEquals(PureRecipePlanner.Status.SUCCESS, result.status(), result.toString());
            assertTrue(result.steps().size() <= 4 * levels + 1, result.toString());
            assertTrue(result.remaining().isEmpty(), result.toString());
            assertFlow(scenario, result);
        }
    }

    @Test
    void splitSixAndFourAggregatesCommonUpstreamBeforeEitherRouteConsumesIt() {
        Scenario scenario = sharedRoutes(10, false);
        var result = plan(scenario, 20);
        assertEquals(13, result.steps().size());
        assertEquals(1, result.steps().stream().filter(step -> step.recipeId().equals(id("upstream"))).count());
        assertEquals(5L, result.steps().get(0).batches());
        assertEquals(6L, result.steps().stream().filter(step -> step.recipeId().equals(id("a_shared")))
                .mapToLong(PureRecipePlanner.PlannedStep::batches).sum());
        assertEquals(4L, result.steps().stream().filter(step -> step.recipeId().equals(id("b_shared")))
                .mapToLong(PureRecipePlanner.PlannedStep::batches).sum());
        assertTrue(result.remaining().isEmpty());
        assertFlow(scenario, result);
    }

    @Test
    void sharedRouteSelectionRollsBackAcrossCompletedConsumers() {
        Scenario scenario = sharedRoutes(6, true);
        var result = plan(scenario, 10);
        assertEquals(8, result.steps().size());
        assertTrue(result.backtracks() > 6, result.toString());
        assertFalse(result.steps().stream().anyMatch(step -> step.recipeId().equals(id("a_shared"))));
        assertEquals(6L, result.steps().stream().filter(step -> step.recipeId().equals(id("b_shared")))
                .mapToLong(PureRecipePlanner.PlannedStep::batches).sum());
        assertTrue(result.remaining().isEmpty());
        assertFlow(scenario, result);
    }

    @Test
    void routeChoicesDoNotSurviveInventoryChangesAndRoundOnlyCombinedDemand() {
        MaterialRef raw = material("raw");
        MaterialRef upstream = material("upstream");
        MaterialRef shared = material("shared");
        MaterialRef tokenA = material("token_a");
        MaterialRef tokenB = material("token_b");
        MaterialRef left = material("left");
        MaterialRef right = material("right");
        var graph = new ImmutableRecipeGraph(Map.of(
                upstream, List.of(recipe("upstream", upstream, 2L, need(raw, 1L))),
                shared, List.of(recipe("a_shared", shared, 3L, need(upstream, 1L), need(tokenA, 1L)),
                        recipe("b_shared", shared, 4L, need(upstream, 2L), need(tokenB, 1L))),
                left, List.of(recipe("left", left, 1L, need(shared, 1L))),
                right, List.of(recipe("right", right, 1L, need(shared, 1L)))));
        var roots = List.of(need(left, 1L), need(right, 1L));
        var cache = new DeterministicRecipePlanner.CompilationCache(graph, Map.of(), PlanningPreferences.empty(), 65_536);
        for (boolean useA : new boolean[] {true, false, true}) {
            var stock = Map.of(raw, 1L, useA ? tokenA : tokenB, 1L);
            assertNotNull(cache.prepare(stock, roots, Set.of(), 10, 65_536, () -> {}));
            var routes = cache.routes(material -> graph.recipesByOutput().getOrDefault(material, List.of()),
                    budgetCheck -> new PureRecipePlanner.SeededReachability(graph, stock, budgetCheck));
            var prepared = routes.prepare(stock, roots, Set.of(), 10, 65_536, () -> {});
            if (prepared.plan() == null) prepared = routes.prepare(stock, roots, Set.of(), 10, 65_536, () -> {});
            assertNotNull(prepared.plan());
            Scenario scenario = new Scenario(graph, stock, roots);
            var result = plan(scenario, 10);
            assertEquals(4, result.steps().size());
            assertEquals(1L, result.steps().stream().filter(step -> step.recipeId().equals(id(useA ? "a_shared" : "b_shared")))
                    .mapToLong(PureRecipePlanner.PlannedStep::batches).sum());
            assertEquals(useA ? Map.of(shared, 1L, upstream, 1L) : Map.of(shared, 2L), result.remaining());
            assertFlow(scenario, result);
        }
    }

    private static Scenario sharedRoutes(int consumers, boolean laterConflict) {
        Map<MaterialRef, List<RecipeNode>> recipes = new LinkedHashMap<>();
        MaterialRef raw = material("raw");
        MaterialRef upstream = material("upstream");
        MaterialRef shared = material("shared");
        MaterialRef rare = material("rare");
        MaterialRef common = material("common");
        recipes.put(upstream, List.of(recipe("upstream", upstream, 2L, need(raw, 1L))));
        recipes.put(shared, List.of(recipe("a_shared", shared, 1L, need(upstream, 1L), need(rare, 1L)),
                recipe("b_shared", shared, 1L, need(upstream, 1L), need(common, 1L))));
        List<IngredientRef> roots = new ArrayList<>();
        for (int index = 0; index < consumers; index++) {
            MaterialRef output = material("consumer_" + index);
            recipes.put(output, List.of(recipe("consumer_" + index, output, 1L, need(shared, 1L))));
            roots.add(need(output, 1L));
        }
        if (laterConflict) roots.add(new IngredientRef(List.of(rare), 6L, NbtMatchMode.PARTIAL));
        return new Scenario(new ImmutableRecipeGraph(recipes),
                Map.of(raw, consumers / 2L, rare, 6L, common, laterConflict ? 6L : 4L), roots);
    }

    private static Scenario nonEquivalentLayers(int levels, boolean bothUsable, boolean split) {
        Map<MaterialRef, List<RecipeNode>> recipes = new LinkedHashMap<>();
        MaterialRef raw = material("raw");
        MaterialRef left = raw;
        MaterialRef right = raw;
        for (int level = 1; level <= levels; level++) {
            MaterialRef nextLeft = material("left_" + level);
            MaterialRef nextRight = material("right_" + level);
            for (MaterialRef output : List.of(nextLeft, nextRight)) {
                String name = output.itemId().getPath();
                RecipeNode first = bothUsable
                        ? recipe(name + "_a", output, 1L, need(left, 1L), need(right, 1L), need(material("token_a"), 1L))
                        : recipe(name + "_a", output, 1L, need(left, 1L), need(right, 1L));
                RecipeNode second = bothUsable
                        ? recipe(name + "_b", output, 1L, need(left, 1L), need(right, 1L), need(material("token_b"), 1L))
                        : recipe(name + "_b", output, 1L, need(material("unavailable_raw"), 1L));
                recipes.put(output, List.of(first, second));
            }
            left = nextLeft;
            right = nextRight;
        }
        MaterialRef root = material("root");
        recipes.put(root, List.of(recipe("root", root, 1L, need(left, 1L), need(right, 1L))));
        Map<MaterialRef, Long> stock = new LinkedHashMap<>();
        long quantity = 1L << (levels + 1);
        stock.put(raw, quantity);
        if (bothUsable) {
            stock.put(material("token_a"), split ? (quantity - 2L) / 2L : quantity);
            stock.put(material("token_b"), split ? (quantity - 2L) / 2L : quantity);
        }
        return new Scenario(new ImmutableRecipeGraph(recipes), stock, List.of(need(root, 1L)));
    }

    @Test
    void localComplexBoundaryKeepsTheOuterCoProductGraphAggregated() {
        for (boolean partial : new boolean[] {false, true}) {
            Scenario original = layered(14, 1, true);
            Map<MaterialRef, List<RecipeNode>> recipes = new LinkedHashMap<>(original.graph().recipesByOutput());
            MaterialRef bottom = material("left_1");
            RecipeNode first = recipes.get(bottom).get(0);
            if (partial) {
                recipes.put(bottom, List.of(new RecipeNode(first.recipeId(), bottom, 1L,
                        List.of(new IngredientRef(List.of(material("raw")), 2L, NbtMatchMode.PARTIAL)),
                        first.modTypeId(), first.recipeTypeId(), first.magicCosts(), false, false, first.guaranteedOutputs())));
            } else {
                recipes.put(bottom, List.of(first,
                        withSide(recipe("unavailable", bottom, 1L, need(material("absent"), 2L)), material("left_1_side"))));
            }
            Scenario scenario = new Scenario(new ImmutableRecipeGraph(recipes), original.stock(), original.roots());
            var result = PureRecipePlanner.resolve(scenario.graph(), scenario.stock(), scenario.roots(),
                    65_536, 65_536, 8_192);
            assertEquals(PureRecipePlanner.Status.SUCCESS, result.status());
            assertEquals(29, result.steps().size());
            assertEquals(86, result.materialFlow().events().size());
            assertTrue(result.expandedStates() < 2_000);
            assertFlow(scenario, result);
        }
    }

    @Test
    void localBoundaryKeepsSplitRoutesAndReevaluatesStock() {
        Scenario original = shared(10, 1L, 1L, 0L);
        Map<MaterialRef, List<RecipeNode>> recipes = new LinkedHashMap<>();
        original.graph().recipesByOutput().forEach((material, candidates) ->
                recipes.put(material, List.of(withSide(candidates.get(0), material(material.itemId().getPath() + "_side")))));
        MaterialRef shared = material("shared");
        RecipeNode first = recipes.get(shared).get(0);
        RecipeNode second = withSide(recipe("second", shared, 1L, need(material("other_raw"), 1L)), material("shared_side"));
        recipes.put(shared, List.of(first, second));
        var graph = new ImmutableRecipeGraph(recipes);
        var cache = new DeterministicRecipePlanner.CompilationCache(graph, Map.of(), PlanningPreferences.empty(), 65_536);
        for (var stock : List.of(Map.of(material("raw"), 6L, material("other_raw"), 4L),
                Map.of(material("raw"), 10L), Map.of(material("other_raw"), 10L))) {
            assertNotNull(cache.prepare(stock, original.roots(), Set.of(), 20, 65_536, () -> {}));
            Scenario scenario = new Scenario(graph, stock, original.roots());
            var result = plan(scenario, 20);
            long firstBatches = result.steps().stream().filter(step -> step.recipeId().equals(first.recipeId()))
                    .mapToLong(PureRecipePlanner.PlannedStep::batches).sum();
            long secondBatches = result.steps().stream().filter(step -> step.recipeId().equals(second.recipeId()))
                    .mapToLong(PureRecipePlanner.PlannedStep::batches).sum();
            assertEquals(stock.getOrDefault(material("raw"), 0L), firstBatches);
            assertEquals(stock.getOrDefault(material("other_raw"), 0L), secondBatches);
            assertEquals(10L, result.remaining().get(material("shared_side")));
            assertTrue(result.steps().size() <= 12);
            assertFlow(scenario, result);
        }
    }

    @Test
    void localBoundaryChoiceRollsBackAfterCoProductConsumersFinish() {
        MaterialRef shared = material("shared");
        MaterialRef rare = material("rare");
        MaterialRef common = material("common");
        MaterialRef left = material("left");
        MaterialRef right = material("right");
        var graph = new ImmutableRecipeGraph(Map.of(
                shared, List.of(recipe("a_rare", shared, 2L, need(rare, 1L)),
                        recipe("b_common", shared, 2L, need(common, 1L))),
                left, List.of(withSide(recipe("left", left, 1L, need(shared, 1L)), material("left_side"))),
                right, List.of(withSide(recipe("right", right, 1L, need(shared, 1L)), material("right_side")))));
        Scenario scenario = new Scenario(graph, Map.of(rare, 1L, common, 1L),
                List.of(need(left, 1L), need(right, 1L), new IngredientRef(List.of(rare), 1L, NbtMatchMode.PARTIAL)));
        var result = plan(scenario, 3);
        assertEquals(id("b_common"), result.steps().get(0).recipeId());
        assertTrue(result.backtracks() > 0);
        assertEquals(Map.of(material("left_side"), 1L, material("right_side"), 1L), result.remaining());
        assertFlow(scenario, result);
    }

    @Test
    void boundarySafetyIncludesCurrentlyUnavailableAlternativeWithCoProductInput() {
        MaterialRef raw = material("raw");
        MaterialRef output = material("output");
        MaterialRef side = material("side");
        MaterialRef boundary = material("boundary");
        RecipeNode producer = new RecipeNode(id("producer"), output, 1L, List.of(need(raw, 1L)),
                "generic", id("crafting"), List.of(), false, false, Map.of(output, 1L, side, 2L));
        var graph = new ImmutableRecipeGraph(Map.of(output, List.of(producer),
                boundary, List.of(recipe("missing", boundary, 1L, need(material("absent"), 1L)),
                        recipe("uses_side", boundary, 1L, need(side, 1L)))));
        for (var roots : List.of(List.of(need(output, 1L), need(boundary, 1L), need(side, 1L)),
                List.of(need(side, 1L), need(boundary, 1L), need(output, 1L)))) {
            Scenario scenario = new Scenario(graph, Map.of(raw, 1L), roots);
            var result = PureRecipePlanner.resolve(graph, scenario.stock(), roots, 3);
            assertEquals(PureRecipePlanner.Status.SUCCESS, result.status(), roots + " " + result);
            assertEquals(2, result.steps().size());
            assertTrue(result.remaining().isEmpty());
            assertFlow(scenario, result);
        }
    }

    @Test
    void layeredSharedGraphStaysCompactWithCoProductsAndEquivalentRoutes() {
        for (int levels : new int[] {10, 12, 14}) {
            for (int alternatives : new int[] {1, 2}) {
                for (boolean coProducts : new boolean[] {false, true}) {
                    Scenario scenario = layered(levels, alternatives, coProducts);
                    var result = PureRecipePlanner.resolve(scenario.graph(), scenario.stock(), scenario.roots(),
                            65_536, 65_536, 8_192);
                    assertEquals(PureRecipePlanner.Status.SUCCESS, result.status());
                    assertEquals(2 * levels + 1, result.steps().size());
                    assertEquals(2 * levels * alternatives + 1, scenario.graph().recipesById().size());
                    assertEquals(6 * levels + 2, result.materialFlow().events().size());
                    assertTrue(result.expandedStates() < 40 * (2 * levels + 1));
                    assertEquals(0, result.backtracks());
                    assertFlow(scenario, result);
                }
            }
        }
    }

    @Test
    void wideSharedGraphsAggregateThousandsOfCoProductAndEquivalentProducers() {
        for (int consumers : new int[] {3_000, 10_000}) {
            Scenario original = shared(consumers, 3L, 2L, 1L);
            Map<MaterialRef, List<RecipeNode>> recipes = new LinkedHashMap<>();
            original.graph().recipesByOutput().forEach((output, candidates) -> {
                RecipeNode first = withSide(candidates.get(0), material(output.itemId().getPath() + "_side"));
                recipes.put(output, List.of(first, copyWithId(first, id(first.recipeId().getPath() + "_other"))));
            });
            Scenario scenario = new Scenario(new ImmutableRecipeGraph(recipes), original.stock(), original.roots());
            var result = plan(scenario, consumers + 1);
            assertEquals(consumers + 1, result.steps().size());
            assertEquals(2 * (consumers + 1), scenario.graph().recipesById().size());
            assertEquals(3 * consumers + 2, result.materialFlow().events().size());
            assertEquals(3L * consumers / 2L, result.steps().get(0).batches());
            assertEquals(1L, result.remaining().get(material("shared")));
            assertEquals(0, result.backtracks());
            assertFlow(scenario, result);
        }
    }

    @Test
    void coProductOverlapAndHiddenBoundaryDependenciesUseSearch() {
        MaterialRef raw = material("raw");
        MaterialRef main = material("main");
        MaterialRef side = material("side");
        RecipeNode producer = withSide(recipe("joint", main, 1L, need(raw, 1L)), side);
        var graph = new ImmutableRecipeGraph(Map.of(main, List.of(producer)));
        for (var roots : List.of(List.of(need(side, 3L), need(main, 3L)),
                List.of(need(main, 3L), need(side, 3L)))) {
            Scenario scenario = new Scenario(graph, Map.of(raw, 3L), roots);
            assertNull(aggregate(scenario, 4));
            var result = plan(scenario, 4);
            assertEquals(1, result.steps().size());
            assertTrue(result.remaining().isEmpty());
            assertFlow(scenario, result);
        }
        MaterialRef target = material("target");
        RecipeNode hidden = recipe("hidden", target, 1L,
                new IngredientRef(List.of(side), 1L, NbtMatchMode.PARTIAL));
        Scenario hiddenUse = new Scenario(new ImmutableRecipeGraph(Map.of(main, List.of(producer),
                target, List.of(hidden))), Map.of(raw, 1L), List.of(need(main, 1L), need(target, 1L)));
        assertNull(aggregate(hiddenUse, 4));
        assertFlow(hiddenUse, plan(hiddenUse, 4));
    }

    @Test
    void twentyThousandIndependentProducersKeepOneRecordPerRecipe() {
        Map<MaterialRef, List<RecipeNode>> recipes = new LinkedHashMap<>();
        List<IngredientRef> roots = new ArrayList<>();
        MaterialRef raw = material("raw");
        for (int index = 0; index < 20_000; index++) {
            MaterialRef output = material("wide_" + index);
            recipes.put(output, List.of(recipe("wide_" + index, output, 1L, need(raw, 1L))));
            roots.add(need(output, 1L));
        }
        Scenario scenario = new Scenario(new ImmutableRecipeGraph(recipes), Map.of(raw, 20_000L), roots);
        var result = plan(scenario, 20_000);
        assertEquals(20_000, result.steps().size());
        assertEquals(60_000, result.materialFlow().events().size());
        assertEquals(0, result.backtracks());
        assertTrue(result.remaining().isEmpty());
        assertFlow(scenario, result);
    }

    @Test
    void equivalentSubsetCannotHideAnotherRouteNeededForSplitProduction() {
        MaterialRef firstRaw = material("first_raw");
        MaterialRef secondRaw = material("second_raw");
        MaterialRef output = material("output");
        RecipeNode first = withSide(recipe("first", output, 1L, need(firstRaw, 1L)), material("side"));
        RecipeNode equivalent = copyWithId(first, id("first_other"));
        RecipeNode second = withSide(recipe("second", output, 1L, need(secondRaw, 1L)), material("side"));
        var graph = new ImmutableRecipeGraph(Map.of(output, List.of(first, equivalent, second)));
        Scenario scenario = new Scenario(graph, Map.of(firstRaw, 6L, secondRaw, 4L), List.of(need(output, 10L)));
        assertNull(aggregate(scenario, 10));
        var result = plan(scenario, 10);
        assertEquals(6L, result.steps().stream().filter(step -> !step.recipeId().equals(second.recipeId()))
                .mapToLong(PureRecipePlanner.PlannedStep::batches).sum());
        assertEquals(4L, result.steps().stream().filter(step -> step.recipeId().equals(second.recipeId()))
                .mapToLong(PureRecipePlanner.PlannedStep::batches).sum());
        assertEquals(Map.of(material("side"), 10L), result.remaining());
        assertFlow(scenario, result);
    }

    @Test
    void differentCoProductsAndNbtFamilyOverlapRemainBoundaries() {
        MaterialRef raw = material("raw");
        MaterialRef output = material("output");
        MaterialRef side = material("side");
        RecipeNode first = withSide(recipe("first", output, 1L, need(raw, 1L)), side);
        RecipeNode second = withSide(recipe("second", output, 1L, need(raw, 1L)), material("other_side"));
        assertNull(aggregate(new Scenario(new ImmutableRecipeGraph(Map.of(output, List.of(first, second))),
                Map.of(raw, 1L), List.of(need(output, 1L))), 4));
        MaterialRef tagged = new MaterialRef(side.itemId(), "{variant:1}");
        RecipeNode taggedProducer = withSide(recipe("tagged", output, 1L, need(raw, 1L)), tagged);
        Scenario scenario = new Scenario(new ImmutableRecipeGraph(Map.of(output, List.of(taggedProducer))),
                Map.of(raw, 1L), List.of(any(side, 1L), need(output, 1L)));
        assertNull(aggregate(scenario, 4));
        var result = plan(scenario, 4);
        assertTrue(result.remaining().isEmpty());
        assertFlow(scenario, result);
    }

    @Test
    void failedCompactCoProductBranchRestoresEveryOutput() {
        MaterialRef target = material("target");
        MaterialRef rare = material("rare");
        MaterialRef common = material("common");
        MaterialRef left = material("left");
        MaterialRef right = material("right");
        MaterialRef side = material("side");
        var graph = new ImmutableRecipeGraph(Map.of(
                target, List.of(recipe("a_target", target, 1L, need(left, 1L)),
                        recipe("b_target", target, 1L, need(right, 1L))),
                left, List.of(withSide(recipe("left", left, 1L, need(rare, 1L)), side)),
                right, List.of(withSide(recipe("right", right, 1L, need(common, 1L)), side))));
        Scenario scenario = new Scenario(graph, Map.of(rare, 1L, common, 1L),
                List.of(need(target, 1L), new IngredientRef(List.of(rare), 1L, NbtMatchMode.PARTIAL)));
        var result = plan(scenario, 4);
        assertTrue(result.backtracks() > 0);
        assertEquals(Map.of(side, 1L), result.remaining());
        assertEquals(id("b_target"), result.steps().get(1).recipeId());
        assertFlow(scenario, result);
    }

    @Test
    void coProductProgramReevaluatesStockAndDoesNotCacheShortage() {
        Scenario scenario = layered(12, 2, true);
        var cache = new DeterministicRecipePlanner.CompilationCache(scenario.graph(), Map.of(),
                PlanningPreferences.empty(), 65_536);
        assertNull(cache.prepare(Map.of(), scenario.roots(), Set.of(), 100, 65_536, () -> {}).plan());
        var success = cache.prepare(scenario.stock(), scenario.roots(), Set.of(), 100, 65_536, () -> {});
        assertNotNull(success.plan());
        assertEquals(25, success.plan().steps().size());
        assertNull(cache.prepare(Map.of(material("raw"), 1L), scenario.roots(), Set.of(), 100, 65_536, () -> {}).plan());
        assertNotNull(cache.prepare(scenario.stock(), scenario.roots(), Set.of(), 100, 65_536, () -> {}).plan());
    }

    @Test
    void equivalentRoutesRespectPreferenceExclusionAndForcedChoice() {
        MaterialRef output = material("output");
        RecipeNode first = recipe("a", output, 1L, need(material("raw"), 1L));
        RecipeNode second = copyWithId(first, id("b"));
        var graph = new ImmutableRecipeGraph(Map.of(output, List.of(second, first)));
        var stock = Map.of(material("raw"), 1L);
        var roots = List.of(need(output, 1L));
        for (PlanningPreferences preferences : List.of(
                new PlanningPreferences(Map.of(output.itemId().toString(), new RecipeChoice.Normal(second.recipeId())), Map.of(), Set.of()),
                new PlanningPreferences(Map.of(), Map.of(), Set.of(first.recipeId())))) {
            var plan = DeterministicRecipePlanner.tryPlan(graph, stock, roots, Set.of(), Map.of(), preferences, 2, 100, () -> {});
            assertNotNull(plan);
            assertEquals(second.recipeId(), plan.steps().get(0).recipeId());
        }
        var forced = DeterministicRecipePlanner.tryPlan(graph, stock, roots, Set.of(),
                Map.of(output.itemId(), second.recipeId()), PlanningPreferences.empty(), 2, 100, () -> {});
        assertEquals(second.recipeId(), forced.steps().get(0).recipeId());
    }

    private static Scenario layered(int levels, int alternatives, boolean coProducts) {
        Map<MaterialRef, List<RecipeNode>> recipes = new LinkedHashMap<>();
        MaterialRef left = material("raw");
        MaterialRef right = left;
        for (int level = 1; level <= levels; level++) {
            MaterialRef nextLeft = material("left_" + level);
            MaterialRef nextRight = material("right_" + level);
            for (MaterialRef output : List.of(nextLeft, nextRight)) {
                RecipeNode first = recipe(output.itemId().getPath(), output, 1L, need(left, 1L), need(right, 1L));
                if (coProducts) first = withSide(first, material(output.itemId().getPath() + "_side"));
                recipes.put(output, alternatives == 1 ? List.of(first)
                        : List.of(first, copyWithId(first, id(first.recipeId().getPath() + "_other"))));
            }
            left = nextLeft;
            right = nextRight;
        }
        MaterialRef root = material("root");
        recipes.put(root, List.of(recipe("root", root, 1L, need(left, 1L), need(right, 1L))));
        return new Scenario(new ImmutableRecipeGraph(recipes), Map.of(material("raw"), 1L << (levels + 1)),
                List.of(need(root, 1L)));
    }

    private static RecipeNode withSide(RecipeNode recipe, MaterialRef side) {
        return new RecipeNode(recipe.recipeId(), recipe.output(), recipe.outputCount(), recipe.inputs(),
                recipe.modTypeId(), recipe.recipeTypeId(), recipe.magicCosts(), false, false,
                Map.of(recipe.output(), recipe.outputCount(), side, 1L));
    }

    private static RecipeNode copyWithId(RecipeNode recipe, ResourceLocation id) {
        return new RecipeNode(id, recipe.output(), recipe.outputCount(), recipe.inputs(),
                recipe.modTypeId(), recipe.recipeTypeId(), recipe.magicCosts(), recipe.hasFluidOutputs(),
                recipe.dynamicIdentityTargetable(), recipe.guaranteedOutputs());
    }

    @Test
    void partiallyStockedChainDoesNotRecompileEveryLayer() {
        Scenario scenario = chain(1000, 1L, false);
        Map<MaterialRef, Long> stock = new LinkedHashMap<>();
        scenario.graph().recipesByOutput().keySet().forEach(material -> stock.put(material, 1L));
        stock.put(material("raw"), 2L);
        var roots = List.of(need(scenario.roots().get(0).alternatives().get(0), 1002L));
        var cache = new DeterministicRecipePlanner.CompilationCache(scenario.graph(), Map.of(),
                PlanningPreferences.empty(), 10000);
        var prepared = cache.prepare(stock, roots, Set.of(), 1000, 10000, () -> {});
        assertNotNull(prepared);
        assertNotNull(prepared.plan());
        assertEquals(2, cache.compilationCount());
        assertEquals(1000, prepared.plan().steps().size());
        assertTrue(prepared.plan().remaining().isEmpty());
        assertFlow(new Scenario(scenario.graph(), stock, roots), plan(new Scenario(scenario.graph(), stock, roots), 1000));
    }

    @Test
    void stockedIntermediateDoesNotCompileItsTenThousandRecipeAncestors() {
        Scenario scenario = chain(10_000, 1L, false);
        MaterialRef ready = scenario.roots().get(0).alternatives().get(0);
        var cache = new DeterministicRecipePlanner.CompilationCache(scenario.graph(), Map.of(),
                PlanningPreferences.empty(), 65_536);
        int[] work = {0};
        cache.prepare(Map.of(ready, 1L), scenario.roots(), Set.of(), 10_000, 65_536, () -> work[0]++);
        assertTrue(work[0] < 100, "已有中间产物不应展开其整条生产链，实际探测次数=" + work[0]);
    }

    @Test
    void depletedStockBoundaryExpandsAgainAndStillSharesConsumerDemand() {
        Scenario scenario = chain(20, 1L, false);
        MaterialRef ready = scenario.roots().get(0).alternatives().get(0);
        var cache = new DeterministicRecipePlanner.CompilationCache(scenario.graph(), Map.of(),
                PlanningPreferences.empty(), 10_000);
        cache.prepare(Map.of(ready, 2L), scenario.roots(), Set.of(), 20, 10_000, () -> {});
        var supplied = cache.prepare(Map.of(ready, 1L, material("raw"), 2L),
                List.of(need(ready, 3L)), Set.of(), 20, 10_000, () -> {});
        assertNotNull(supplied);
        assertNotNull(supplied.plan());
        assertEquals(20, supplied.plan().steps().size());
        assertTrue(supplied.plan().steps().stream().allMatch(step -> step.batches() == 2L));
        assertTrue(supplied.plan().remaining().isEmpty());
    }

    @Test
    void tenThousandRecipeChainWithTagBoundaryDoesNotFallBackAtEveryAncestor() {
        Map<MaterialRef, List<RecipeNode>> recipes = new LinkedHashMap<>();
        MaterialRef raw = material("tagged_chain_raw");
        MaterialRef previous = material("tagged_chain_0");
        recipes.put(previous, List.of(recipe("tagged_chain_0", previous, 1L,
                new IngredientRef(List.of(raw, material("missing_raw")), 1L, NbtMatchMode.ANY))));
        for (int index = 1; index < 10_000; index++) {
            MaterialRef output = material("tagged_chain_" + index);
            recipes.put(output, List.of(recipe("tagged_chain_" + index, output, 1L, any(previous, 1L))));
            previous = output;
        }
        Scenario scenario = new Scenario(new ImmutableRecipeGraph(recipes), Map.of(raw, 50_000_000L),
                List.of(any(previous, 50_000_000L)));
        var result = plan(scenario, 10_000);
        assertEquals(10_000, result.steps().size());
        assertTrue(result.steps().stream().allMatch(step -> step.batches() == 50_000_000L));
        assertEquals(0, result.backtracks());
        assertFlow(scenario, result);
    }

    @Test
    void multipleComplexBoundariesCanShareAProducedIntermediate() {
        MaterialRef raw = material("raw");
        MaterialRef shared = material("shared");
        MaterialRef boundaryA = material("boundary_a");
        MaterialRef boundaryB = material("boundary_b");
        MaterialRef target = material("target");
        var graph = new ImmutableRecipeGraph(Map.of(
                shared, List.of(recipe("shared", shared, 2L, need(raw, 1L))),
                boundaryA, List.of(recipe("boundary_a", boundaryA, 1L,
                        new IngredientRef(List.of(shared, material("absent_a")), 1L, NbtMatchMode.ANY))),
                boundaryB, List.of(recipe("boundary_b", boundaryB, 1L,
                        new IngredientRef(List.of(shared, material("absent_b")), 1L, NbtMatchMode.ANY))),
                target, List.of(recipe("target", target, 1L, need(boundaryA, 1L), need(boundaryB, 1L)))));
        Scenario scenario = new Scenario(graph, Map.of(raw, 1L), List.of(need(target, 1L)));
        var result = plan(scenario, 4);
        assertEquals(4, result.steps().size());
        assertFlow(scenario, result);
    }

    @Test
    void boundaryDependencyAcrossScheduledRootsFallsBackWithoutPoisoningSearch() {
        MaterialRef raw = material("raw");
        MaterialRef intermediate = material("intermediate");
        MaterialRef boundary = material("boundary");
        MaterialRef target = material("target");
        var graph = new ImmutableRecipeGraph(Map.of(
                intermediate, List.of(recipe("intermediate", intermediate, 1L, need(raw, 1L))),
                boundary, List.of(recipe("boundary", boundary, 1L,
                        new IngredientRef(List.of(intermediate, material("absent")), 1L, NbtMatchMode.ANY))),
                target, List.of(recipe("target", target, 1L, need(boundary, 1L)))));
        Scenario scenario = new Scenario(graph, Map.of(raw, 2L),
                List.of(need(target, 1L), need(intermediate, 1L)));
        var result = plan(scenario, 4);
        assertEquals(4, result.steps().size());
        assertFlow(scenario, result);
    }

    @Test
    void stockedFirstRootCannotReuseInitialPreparationForADifferentSuffix() {
        MaterialRef stored = material("stored");
        MaterialRef raw = material("raw");
        MaterialRef boundary = material("boundary");
        MaterialRef target = material("target");
        var graph = new ImmutableRecipeGraph(Map.of(
                boundary, List.of(recipe("boundary", boundary, 1L,
                        new IngredientRef(List.of(raw, material("absent")), 1L, NbtMatchMode.ANY))),
                target, List.of(recipe("target", target, 1L, need(boundary, 1L)))));
        Scenario scenario = new Scenario(graph, Map.of(stored, 1L, raw, 1L),
                List.of(need(stored, 1L), need(target, 1L)));
        assertFlow(scenario, plan(scenario, 2));
    }

    @Test
    void sharedTagBoundaryIsProducedOnceForAllConsumersAtAnyQuantity() {
        MaterialRef raw = material("boundary_raw");
        MaterialRef shared = material("boundary_shared");
        MaterialRef left = material("boundary_left");
        MaterialRef right = material("boundary_right");
        var graph = new ImmutableRecipeGraph(Map.of(
                shared, List.of(recipe("boundary_shared", shared, 4L,
                        new IngredientRef(List.of(raw, material("absent_variant")), 1L, NbtMatchMode.ANY))),
                left, List.of(recipe("boundary_left", left, 1L, need(shared, 3L))),
                right, List.of(recipe("boundary_right", right, 1L, need(shared, 3L)))));
        int states = -1;
        for (long amount : new long[] {1L, 50_000_000L}) {
            long batches = (6L * amount + 3L) / 4L;
            Scenario scenario = new Scenario(graph, Map.of(raw, batches),
                    List.of(need(left, amount), need(right, amount)));
            var result = plan(scenario, 3);
            assertEquals(3, result.steps().size());
            assertEquals(new PureRecipePlanner.PlannedStep(id("boundary_shared"), batches), result.steps().get(0));
            if (states >= 0) assertEquals(states, result.expandedStates());
            states = result.expandedStates();
            assertFlow(scenario, result);
        }
    }

    @Test
    void compilationIsReusedWhileStockQuantitiesAndAncestorsAreReevaluated() {
        Scenario scenario = chain(20, 1L, false);
        var cache = new DeterministicRecipePlanner.CompilationCache(scenario.graph(), Map.of(),
                PlanningPreferences.empty(), 10_000);
        var missing = cache.prepare(Map.of(), scenario.roots(), Set.of(), 20, 10_000, () -> {});
        assertNotNull(missing);
        assertNull(missing.plan());
        var supplied = cache.prepare(scenario.stock(), scenario.roots(), Set.of(), 20, 10_000, () -> {});
        assertNotNull(supplied.plan());
        assertEquals(20, supplied.plan().steps().size());
        var scaled = cache.prepare(Map.of(material("raw"), 50_000_000L),
                List.of(scenario.roots().get(0).withCount(50_000_000L)), Set.of(), 20, 10_000, () -> {});
        assertTrue(scaled.plan().steps().stream().allMatch(step -> step.batches() == 50_000_000L));
        var ancestorBlocked = cache.prepare(scenario.stock(), scenario.roots(), Set.of(material("chain_10")),
                20, 10_000, () -> {});
        assertNull(ancestorBlocked.plan());
        assertTrue(ancestorBlocked.schedule().stream().anyMatch(entry ->
                entry instanceof DeterministicRecipePlanner.Boundary boundary
                        && boundary.ingredient().alternatives().contains(material("chain_10"))));
        assertNotNull(cache.prepare(scenario.stock(), scenario.roots(), Set.of(), 20, 10_000, () -> {}).plan());
        assertEquals(1, cache.compilationCount());
    }

    @Test
    void zeroCacheCapacityStillPlansWithoutRetainingPrograms() {
        Scenario scenario = chain(20, 1L, false);
        var cache = new DeterministicRecipePlanner.CompilationCache(scenario.graph(), Map.of(),
                PlanningPreferences.empty(), 0);
        var first = cache.prepare(scenario.stock(), scenario.roots(), Set.of(), 20, 10_000, () -> {});
        var second = cache.prepare(scenario.stock(), scenario.roots(), Set.of(), 20, 10_000, () -> {});
        assertEquals(first.plan(), second.plan());
        assertEquals(2, cache.compilationCount());
    }

    @Test
    void boundarySupplyPreservesExactNbtAndDoesNotCreateSyntheticConsumption() {
        MaterialRef raw = material("raw");
        MaterialRef shared = material("shared");
        MaterialRef tagged = new MaterialRef(shared.itemId(), "{variant:1}");
        MaterialRef flexible = material("flexible");
        MaterialRef exact = material("exact");
        var graph = new ImmutableRecipeGraph(Map.of(
                shared, List.of(recipe("a_shared", shared, 1L, need(raw, 1L)),
                        recipe("b_shared", shared, 1L, need(material("absent"), 1L))),
                flexible, List.of(recipe("flexible", flexible, 1L, any(shared, 1L))),
                exact, List.of(recipe("exact", exact, 1L, need(shared, 1L)))));
        for (var roots : List.of(List.of(need(flexible, 2L), need(exact, 1L)),
                List.of(need(exact, 1L), need(flexible, 2L)))) {
            Scenario scenario = new Scenario(graph, Map.of(raw, 1L, tagged, 2L), roots);
            var result = plan(scenario, 3);
            assertTrue(result.remaining().isEmpty());
            assertFlow(scenario, result);
        }
    }

    @Test
    void boundaryChoiceCanBacktrackAfterCompiledConsumersHaveFinished() {
        MaterialRef rare = material("rare");
        MaterialRef common = material("common");
        MaterialRef shared = material("shared");
        MaterialRef left = material("left");
        MaterialRef right = material("right");
        var graph = new ImmutableRecipeGraph(Map.of(
                shared, List.of(recipe("a_rare", shared, 2L, need(rare, 1L)),
                        recipe("b_common", shared, 2L, need(common, 1L))),
                left, List.of(recipe("left", left, 1L, need(shared, 1L))),
                right, List.of(recipe("right", right, 1L, need(shared, 1L)))));
        Scenario scenario = new Scenario(graph, Map.of(rare, 1L, common, 1L),
                List.of(need(left, 1L), need(right, 1L),
                        new IngredientRef(List.of(rare), 1L, NbtMatchMode.PARTIAL)));
        var result = plan(scenario, 3);
        assertEquals(id("b_common"), result.steps().get(0).recipeId());
        assertEquals(3, result.steps().size());
        assertFlow(scenario, result);
    }

    @Test
    void boundaryCannotProduceAnUnseededAncestorButCanUseAStoredTagAlternative() {
        MaterialRef blank = material("blank_rune");
        MaterialRef scarlet = material("scarlet_rune");
        MaterialRef lightning = material("lightning_rune");
        var graph = new ImmutableRecipeGraph(Map.of(
                blank, List.of(recipe("blank", blank, 1L,
                        new IngredientRef(List.of(scarlet, lightning), 1L, NbtMatchMode.ANY))),
                scarlet, List.of(recipe("scarlet", scarlet, 1L, need(blank, 1L)))));
        var unseeded = PureRecipePlanner.resolve(graph, Map.of(), List.of(need(scarlet, 1L)), 10);
        assertFalse(unseeded.feasible());
        Scenario seeded = new Scenario(graph, Map.of(lightning, 1L), List.of(need(scarlet, 1L)));
        assertFlow(seeded, plan(seeded, 2));
    }

    @Test
    void cachedProgramDoesNotRememberInsufficientStepBudget() {
        Scenario scenario = chain(20, 1L, false);
        var cache = new DeterministicRecipePlanner.CompilationCache(scenario.graph(), Map.of(),
                PlanningPreferences.empty(), 10_000);
        assertNull(cache.prepare(scenario.stock(), scenario.roots(), Set.of(), 19, 10_000, () -> {}));
        assertNotNull(cache.prepare(scenario.stock(), scenario.roots(), Set.of(), 20, 10_000, () -> {}).plan());
        assertEquals(1, cache.compilationCount());
    }

    @Test
    void anyChainAggregatesFiftyMillionWithoutQuantityExpansion() {
        Map<MaterialRef, List<RecipeNode>> recipes = new LinkedHashMap<>();
        MaterialRef previous = material("raw");
        for (int index = 0; index < 10_000; index++) {
            MaterialRef output = material("any_" + index);
            recipes.put(output, List.of(recipe("any_" + index, output, 1L, any(previous, 1L))));
            previous = output;
        }
        int states = -1;
        for (long amount : new long[] {1L, 50_000_000L}) {
            Scenario scenario = new Scenario(new ImmutableRecipeGraph(recipes),
                    Map.of(material("raw"), amount), List.of(any(previous, amount)));
            var result = plan(scenario, 10_000);
            assertEquals(10_000, result.steps().size());
            if (states >= 0) assertEquals(states, result.expandedStates());
            states = result.expandedStates();
            assertEquals(0, result.backtracks());
            assertFlow(scenario, result);
        }
    }

    @Test
    void anyVariantsLeaveTaglessStockForExactDemandInEitherOrder() {
        MaterialRef raw = material("raw");
        MaterialRef taggedA = new MaterialRef(raw.itemId(), "{variant:1}");
        MaterialRef taggedB = new MaterialRef(raw.itemId(), "{variant:2}");
        MaterialRef flexible = material("flexible");
        MaterialRef exact = material("exact");
        var graph = new ImmutableRecipeGraph(Map.of(
                flexible, List.of(recipe("flexible", flexible, 1L, any(raw, 1L))),
                exact, List.of(recipe("exact", exact, 1L, need(raw, 1L)))));
        for (var roots : List.of(List.of(any(flexible, 7L), need(exact, 3L)),
                List.of(need(exact, 3L), any(flexible, 7L)))) {
            Scenario scenario = new Scenario(graph, Map.of(raw, 3L, taggedA, 2L, taggedB, 5L), roots);
            assertNotNull(aggregate(scenario, 2));
            var result = plan(scenario, 2);
            assertTrue(result.remaining().isEmpty());
            assertFlow(scenario, result);
        }
    }

    @Test
    void anyDoesNotHideAnotherNbtProducer() {
        MaterialRef raw = material("raw");
        MaterialRef output = material("output");
        MaterialRef tagged = new MaterialRef(output.itemId(), "{variant:1}");
        var graph = new ImmutableRecipeGraph(Map.of(
                output, List.of(recipe("missing", output, 1L, need(material("missing"), 1L))),
                tagged, List.of(recipe("tagged", tagged, 1L, any(raw, 1L)))));
        Scenario scenario = new Scenario(graph, Map.of(raw, 10L), List.of(any(output, 10L)));
        assertNull(aggregate(scenario, 2));
        var result = plan(scenario, 2);
        assertEquals(id("tagged"), result.steps().get(0).recipeId());
        assertFlow(scenario, result);
    }

    @Test
    void complexBoundaryStillAggregatesSharedSubgraph() {
        Scenario shared = shared(2, 3L, 4L, 0L);
        MaterialRef target = material("target");
        MaterialRef choice = material("choice");
        Map<MaterialRef, List<RecipeNode>> recipes = new LinkedHashMap<>(shared.graph().recipesByOutput());
        recipes.put(target, List.of(recipe("target", target, 1L,
                any(material("consumer_0"), 1L), any(material("consumer_1"), 1L),
                new IngredientRef(List.of(choice, material("absent_choice")), 1L, NbtMatchMode.ANY))));
        Scenario scenario = new Scenario(new ImmutableRecipeGraph(recipes),
                Map.of(material("raw"), 2L, choice, 1L), List.of(any(target, 1L)));
        assertNull(aggregate(scenario, 4));
        var result = plan(scenario, 4);
        var producers = result.steps().stream().filter(step -> step.recipeId().equals(id("shared"))).toList();
        assertEquals(1, producers.size());
        assertEquals(2L, producers.get(0).batches());
        assertEquals(4, result.steps().size());
        assertFlow(scenario, result);
    }

    @Test
    void failedAggregatedBranchRestoresInventoryAndFlow() {
        MaterialRef target = material("target");
        MaterialRef intermediate = material("intermediate");
        MaterialRef rare = material("rare");
        MaterialRef common = material("common");
        MaterialRef commonIntermediate = material("common_intermediate");
        var graph = new ImmutableRecipeGraph(Map.of(
                target, List.of(recipe("a_rare", target, 1L, any(intermediate, 1L)),
                        recipe("b_common", target, 1L, any(commonIntermediate, 1L))),
                intermediate, List.of(recipe("intermediate", intermediate, 1L, any(rare, 1L))),
                commonIntermediate, List.of(recipe("common_intermediate", commonIntermediate, 1L, any(common, 1L)))));
        Scenario scenario = new Scenario(graph, Map.of(rare, 1L, common, 1L),
                List.of(any(target, 1L), new IngredientRef(List.of(rare), 1L, NbtMatchMode.PARTIAL)));
        var result = plan(scenario, 4);
        assertEquals(2, result.steps().size());
        assertEquals(id("b_common"), result.steps().get(1).recipeId());
        assertTrue(result.backtracks() > 0);
        assertFlow(scenario, result);
    }

    @Test
    void laterMultipleProducerBoundaryDoesNotDisableEarlierSharedConsumers() {
        Scenario shared = shared(2, 3L, 4L, 0L);
        MaterialRef target = material("target");
        MaterialRef choice = material("choice");
        Map<MaterialRef, List<RecipeNode>> recipes = new LinkedHashMap<>(shared.graph().recipesByOutput());
        recipes.put(choice, List.of(recipe("choice_a", choice, 1L, need(material("raw"), 1L)),
                recipe("choice_b", choice, 1L, need(material("missing"), 1L))));
        recipes.put(target, List.of(recipe("target", target, 1L,
                any(material("consumer_0"), 1L), any(material("consumer_1"), 1L), any(choice, 1L))));
        Scenario scenario = new Scenario(new ImmutableRecipeGraph(recipes),
                Map.of(material("raw"), 2L, choice, 1L), List.of(any(target, 1L)));
        // 多生产者边界已有足量库存时，整段现在可以直接聚合。
        assertNotNull(aggregate(scenario, 4));
        var result = plan(scenario, 4);
        assertEquals(1, result.steps().stream().filter(step -> step.recipeId().equals(id("shared"))).count());
        assertEquals(4, result.steps().size());
        assertFlow(scenario, result);
    }

    private static IngredientRef any(MaterialRef material, long count) {
        return new IngredientRef(List.of(material), count, NbtMatchMode.ANY);
    }

    @Test
    void tenThousandRecipeChainKeepsQuantitiesAggregated() {
        Scenario chain = chain(10_000, 1L, false);
        int states = -1;
        for (long amount : new long[] {1L, 10_000_000L, 50_000_000L}) {
            Scenario scaled = new Scenario(chain.graph(), Map.of(material("raw"), amount),
                    List.of(chain.roots().get(0).withCount(amount)));
            var result = plan(scaled, 10_000);
            assertEquals(10_000, result.steps().size());
            assertTrue(result.steps().stream().allMatch(step -> step.batches() == amount));
            if (states != -1) assertEquals(states, result.expandedStates());
            states = result.expandedStates();
            assertFlow(scaled, result);
        }
    }

    @Test
    void generalSearchCompletesDeepChainWithoutAggregation() {
        Scenario scenario = chain(2_000, 1L, true);
        assertNull(aggregate(scenario, 2_000));
        var result = plan(scenario, 2_000);
        assertEquals(2_000, result.steps().size());
        assertFlow(scenario, result);
    }

    @Test
    void generalSearchCompletesWideGraphAndStockOnlyRequests() {
        Map<MaterialRef, List<RecipeNode>> recipes = new LinkedHashMap<>();
        Map<MaterialRef, Long> stock = new LinkedHashMap<>();
        List<IngredientRef> roots = new ArrayList<>();
        for (int i = 0; i < 3_000; i++) {
            MaterialRef raw = material("raw_" + i);
            MaterialRef output = material("wide_" + i);
            stock.put(raw, 1L);
            recipes.put(output, List.of(recipe("wide_" + i, output, 1L, need(raw, 1L))));
            roots.add(new IngredientRef(List.of(output), 1L, NbtMatchMode.PARTIAL));
        }
        Scenario wide = new Scenario(new ImmutableRecipeGraph(recipes), stock, roots);
        assertNull(aggregate(wide, 3_000));
        assertFlow(wide, plan(wide, 3_000));
        Scenario stocked = new Scenario(new ImmutableRecipeGraph(Map.of()), stock,
                stock.keySet().stream().map(item -> need(item, 1L)).toList());
        assertFlow(stocked, plan(stocked, 1));
    }

    @Test
    void sharedIntermediateIsProducedOnceForTenThousandConsumers() {
        Scenario scenario = shared(10_000, 1L, 1L, 0L);
        var result = plan(scenario, 10_001);
        assertEquals(10_001, result.steps().size());
        var producers = result.steps().stream().filter(step -> step.recipeId().equals(id("shared"))).toList();
        assertEquals(1, producers.size());
        assertEquals(10_000L, producers.get(0).batches());
        assertFlow(scenario, result);
    }

    @Test
    void aggregateRoundingUsesExistingStockAndPreservesLeftovers() {
        Scenario scenario = shared(3, 5L, 4L, 2L);
        var result = plan(scenario, 4);
        // 三个消费者共需 15 件，中间件已有 2 件，每批生产 4 件，需 4 批。
        assertEquals(4L, result.steps().get(0).batches());
        assertEquals(3L, result.remaining().get(material("shared")));
        assertFlow(scenario, result);
    }

    @Test
    void smallSharedGraphAlsoAggregatesWithoutHiddenSizeThreshold() {
        Scenario scenario = shared(2, 1L, 1L, 0L);
        assertNotNull(aggregate(scenario, 3));
        var result = plan(scenario, 3);
        assertEquals(3, result.steps().size());
        assertEquals(2L, result.steps().get(0).batches());
        assertFlow(scenario, result);
    }

    @Test
    void rollbackAcrossHundredsOfCompletedTasksRestoresStockAndFlow() {
        MaterialRef rare = material("rare");
        MaterialRef common = material("common");
        MaterialRef target = material("target");
        Map<MaterialRef, List<RecipeNode>> recipes = new LinkedHashMap<>();
        recipes.put(target, List.of(recipe("a_spends_rare", target, 1L, need(rare, 1L)),
                recipe("b_spends_common", target, 1L, need(common, 1L))));
        List<IngredientRef> roots = new ArrayList<>(List.of(need(target, 1L)));
        Map<MaterialRef, Long> stock = new LinkedHashMap<>(Map.of(rare, 1L, common, 1L));
        for (int i = 0; i < 700; i++) {
            MaterialRef stored = material("stored_" + i);
            roots.add(need(stored, 1L));
            stock.put(stored, 1L);
        }
        roots.add(need(rare, 1L));
        Scenario scenario = new Scenario(new ImmutableRecipeGraph(recipes), stock, roots);
        var result = plan(scenario, 1);
        assertTrue(result.backtracks() > 700);
        assertEquals(id("b_spends_common"), result.steps().get(0).recipeId());
        assertFlow(scenario, result);
    }

    @Test
    void unsupportedSemanticsFallBackWithoutChangingInventory() {
        Scenario scenario = shared(2, 1L, 1L, 0L);
        var root = scenario.roots().get(0);
        for (IngredientRef unsupported : List.of(
                new IngredientRef(root.alternatives(), 1L, NbtMatchMode.PARTIAL),
                new IngredientRef(root.alternatives(), 1L, NbtMatchMode.EXACT, DemandRole.CATALYST),
                new IngredientRef(root.alternatives(), 1L, NbtMatchMode.EXACT, DemandRole.CONSUMED, false))) {
            assertNull(aggregate(new Scenario(scenario.graph(), scenario.stock(), List.of(unsupported)), 10));
        }
        MaterialRef shared = material("shared");
        Map<MaterialRef, List<RecipeNode>> cyclic = new LinkedHashMap<>(scenario.graph().recipesByOutput());
        cyclic.put(material("raw"), List.of(recipe("cycle", material("raw"), 1L, need(shared, 1L))));
        // 库存已满足 raw 时不必遍历其循环生产者；耗尽库存后仍须拒绝无种子循环。
        Scenario stocked = new Scenario(new ImmutableRecipeGraph(cyclic), scenario.stock(), scenario.roots());
        assertNotNull(aggregate(stocked, 10));
        assertFlow(stocked, plan(stocked, 10));
        assertNull(aggregate(new Scenario(new ImmutableRecipeGraph(cyclic), Map.of(), scenario.roots()), 10));
    }

    @Test
    void forcedExcludedAndNoRecipeChoicesApplyToAggregation() {
        Scenario scenario = shared(2, 1L, 1L, 0L);
        MaterialRef shared = material("shared");
        Map<MaterialRef, List<RecipeNode>> recipes = new LinkedHashMap<>(scenario.graph().recipesByOutput());
        recipes.put(shared, List.of(recipe("shared", shared, 1L, need(material("raw"), 1L)),
                recipe("other", shared, 1L, need(material("absent"), 1L))));
        ImmutableRecipeGraph graph = new ImmutableRecipeGraph(recipes);
        var selected = DeterministicRecipePlanner.tryPlan(graph, scenario.stock(), scenario.roots(), Set.of(),
                Map.of(shared.itemId(), id("shared")), PlanningPreferences.empty(), 10, 1_000, () -> {});
        assertNotNull(selected);
        assertNull(DeterministicRecipePlanner.tryPlan(graph, scenario.stock(), scenario.roots(), Set.of(),
                Map.of(shared.itemId(), id("other")), PlanningPreferences.empty(), 10, 1_000, () -> {}));
        assertNull(DeterministicRecipePlanner.tryPlan(scenario.graph(), scenario.stock(), scenario.roots(), Set.of(shared),
                Map.of(), PlanningPreferences.empty(), 10, 1_000, () -> {}));
        assertNull(DeterministicRecipePlanner.tryPlan(scenario.graph(), scenario.stock(), scenario.roots(), Set.of(),
                Map.of(), new PlanningPreferences(Map.of(), Map.of(), Set.of(id("shared"))), 10, 1_000, () -> {}));
        assertNull(DeterministicRecipePlanner.tryPlan(scenario.graph(), scenario.stock(), scenario.roots(), Set.of(),
                Map.of(), new PlanningPreferences(Map.of(shared.itemId().toString(), RecipeChoice.NoRecipe.INSTANCE),
                        Map.of(), Set.of()), 10, 1_000, () -> {}));
    }

    @Test
    void budgetsStillStopLargePlansWithoutReturningPartialSuccess() {
        Scenario scenario = chain(1_000, 1L, false);
        var states = PureRecipePlanner.resolve(scenario.graph(), scenario.stock(), scenario.roots(),
                1_000, 10, 100);
        assertEquals(PureRecipePlanner.Status.SEARCH_LIMIT, states.status());
        assertEquals(scenario.stock(), states.remaining());
        var steps = PureRecipePlanner.resolve(scenario.graph(), scenario.stock(), scenario.roots(),
                999, 100_000, 100);
        assertEquals(PureRecipePlanner.Status.STEP_LIMIT, steps.status());
        var timed = PureRecipePlanner.resolve(scenario.graph(), scenario.stock(), scenario.roots(),
                1_000, 100_000, 100, 1L, () -> 2L);
        assertEquals(PureRecipePlanner.Status.TIME_LIMIT, timed.status());
    }

    @Test
    void aggregationRejectsOverflowInsteadOfSaturatingTheDemand() {
        Scenario scenario = shared(2, Long.MAX_VALUE, 1L, 0L);
        assertNull(aggregate(scenario, 10));
    }

    private static Scenario chain(int length, long amount, boolean general) {
        Map<MaterialRef, List<RecipeNode>> recipes = new LinkedHashMap<>();
        MaterialRef previous = material("raw");
        for (int i = 0; i < length; i++) {
            MaterialRef output = material("chain_" + i);
            recipes.put(output, List.of(recipe("chain_" + i, output, 1L,
                    new IngredientRef(List.of(previous), 1L, general ? NbtMatchMode.PARTIAL : NbtMatchMode.EXACT))));
            previous = output;
        }
        return new Scenario(new ImmutableRecipeGraph(recipes), Map.of(material("raw"), amount),
                List.of(new IngredientRef(List.of(previous), amount, general ? NbtMatchMode.PARTIAL : NbtMatchMode.EXACT)));
    }

    private static Scenario shared(int consumers, long perConsumer, long outputCount, long existing) {
        Map<MaterialRef, List<RecipeNode>> recipes = new LinkedHashMap<>();
        MaterialRef shared = material("shared");
        recipes.put(shared, List.of(recipe("shared", shared, outputCount, need(material("raw"), 1L))));
        List<IngredientRef> roots = new ArrayList<>();
        for (int i = 0; i < consumers; i++) {
            MaterialRef output = material("consumer_" + i);
            recipes.put(output, List.of(recipe("consumer_" + i, output, 1L, need(shared, perConsumer))));
            roots.add(need(output, 1L));
        }
        Map<MaterialRef, Long> stock = new HashMap<>(Map.of(material("raw"), Long.MAX_VALUE));
        if (existing > 0L) stock.put(shared, existing);
        return new Scenario(new ImmutableRecipeGraph(recipes), stock, roots);
    }

    private static PureRecipePlanner.Result plan(Scenario scenario, int maxSteps) {
        var result = PureRecipePlanner.resolve(scenario.graph(), scenario.stock(), scenario.roots(),
                maxSteps, 200_000, 8_192);
        assertEquals(PureRecipePlanner.Status.SUCCESS, result.status());
        return result;
    }

    private static DeterministicRecipePlanner.Plan aggregate(Scenario scenario, int maxSteps) {
        return DeterministicRecipePlanner.tryPlan(scenario.graph(), scenario.stock(), scenario.roots(),
                Set.of(), Map.of(), PlanningPreferences.empty(), maxSteps, 200_000, () -> {});
    }

    /** 独立重放库存与每个配方端口，检查成功图不漏扣、重复扣料或引用未产出的材料。 */
    private static void assertFlow(Scenario scenario, PureRecipePlanner.Result result) {
        Map<MaterialRef, Long> stock = new HashMap<>(scenario.stock());
        Map<Integer, Map<Integer, IngredientRef>> ports = new HashMap<>();
        Map<Integer, IngredientRef> roots = new HashMap<>();
        for (var event : result.materialFlow().events()) {
            if (event instanceof MaterialUse use) {
                assertEquals(use.demand().count(), use.materials().values().stream().mapToLong(Long::longValue).sum());
                use.materials().forEach((material, count) -> {
                    assertTrue(ImmutableRecipeGraphProjector.matchesIngredient(material, use.demand()),
                            "分配的具体材料必须符合原始需求");
                    long left = stock.getOrDefault(material, 0L) - count;
                    assertTrue(left >= 0L, material.toString());
                    if (left == 0L) stock.remove(material); else stock.put(material, left);
                });
                Map<Integer, IngredientRef> target = use.consumer() < 0 ? roots
                        : ports.computeIfAbsent(use.consumer(), ignored -> new HashMap<>());
                assertNull(target.put(use.port(), use.demand()));
            } else {
                Production production = (Production) event;
                var inputs = PureDemandNormalizer.mergeEquivalent(production.recipe().inputs());
                var supplied = ports.remove(production.node());
                assertEquals(inputs.size(), supplied == null ? 0 : supplied.size());
                for (int port = 0; port < inputs.size(); port++) {
                    assertEquals(inputs.get(port).withCount(Math.multiplyExact(inputs.get(port).count(), production.batches())),
                            supplied.get(port));
                }
                production.recipe().guaranteedOutputs().forEach((material, count) ->
                        stock.merge(material, Math.multiplyExact(count, production.batches()), Math::addExact));
            }
        }
        assertTrue(ports.isEmpty());
        assertEquals(result.materialFlow().roots().size(), roots.size());
        for (int port = 0; port < roots.size(); port++) assertEquals(result.materialFlow().roots().get(port), roots.get(port));
        assertEquals(result.remaining(), stock);
    }

    private static RecipeNode recipe(String name, MaterialRef output, long count, IngredientRef... inputs) {
        return new RecipeNode(id(name), output, count, List.of(inputs));
    }

    private static IngredientRef need(MaterialRef material, long count) {
        return new IngredientRef(List.of(material), count);
    }

    private static MaterialRef material(String name) { return new MaterialRef(id(name), ""); }
    private static ResourceLocation id(String name) { return new ResourceLocation("capacity_test", name); }
    private record Scenario(ImmutableRecipeGraph graph, Map<MaterialRef, Long> stock, List<IngredientRef> roots) {}
}
