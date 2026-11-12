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

import static org.junit.jupiter.api.Assertions.*;

class PlannerCapacityTest extends BootstrapTest {
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
                stock.merge(production.output(), Math.multiplyExact(production.recipe().outputCount(), production.batches()), Math::addExact);
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
