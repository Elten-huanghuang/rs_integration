package com.huanghuang.rsintegration.crafting.planning;

import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.IngredientRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.MaterialRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.RecipeNode;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class RouteSearchPruningTest extends BootstrapTest {
    @Test
    void insufficientRawLeavesDoNotMultiplyRetriesOfEarlierSharedChoices() {
        var scarce = material("scarce");
        var other = material("other");
        var raw = material("raw");
        var shortLeaf = material("short");
        var first = material("first");
        var consumeScarce = recipe("a", first, need(scarce, 1));
        var saveScarce = recipe("b", first, need(other, 1));
        Map<MaterialRef, List<RecipeNode>> recipes = new LinkedHashMap<>();
        recipes.put(first, List.of(consumeScarce, saveScarce));
        List<IngredientRef> roots = new ArrayList<>(List.of(need(first, 1)));
        for (int index = 0; index < 6; index++) {
            var branch = material("part_" + index);
            recipes.put(branch, List.of(recipe("short_" + index, branch, need(raw, 1), need(shortLeaf, 2)),
                    recipe("good_" + index, branch, need(raw, 1))));
            roots.add(need(branch, 1));
        }
        roots.add(need(scarce, 1));
        var graph = new ImmutableRecipeGraph(recipes);
        var stock = Map.of(scarce, 1L, other, 1L, raw, 6L, shortLeaf, 1L);
        var cache = new DeterministicRecipePlanner.CompilationCache(graph, Map.of(),
                PlanningPreferences.empty(), 65_536);
        var cursor = cache.routes(m -> graph.recipesByOutput().getOrDefault(m, List.of()),
                check -> new PureRecipePlanner.SeededReachability(graph, stock, check));
        var work = new AtomicInteger();
        var attempts = 0;
        DeterministicRecipePlanner.Plan plan = null;
        while (plan == null) {
            var prepared = cursor.prepare(stock, roots, Set.of(), 20, 65_536, work::incrementAndGet);
            assertNotNull(prepared, "未找到保留稀缺材料的路线");
            attempts++;
            plan = prepared.plan();
        }
        assertEquals(Map.of(shortLeaf, 1L), plan.remaining());
        assertEquals(7, plan.steps().size());
        assertTrue(plan.steps().stream().anyMatch(step -> step.recipeId().equals(saveScarce.recipeId())));
        assertEquals(2, attempts);
        assertTrue(work.get() < 2_000, "路线试算工作量=" + work.get());
    }

    @Test
    void candidateCanUseAnUnstockedGuaranteedCoProduct() {
        var raw = material("raw");
        var both = material("both");
        var extra = material("extra");
        var missing = material("missing");
        var target = material("target");
        var produceBoth = new RecipeNode(material("joint").itemId(), both, 1, List.of(need(raw, 1)),
                "generic", material("machine").itemId(), List.of(), false, false, Map.of(both, 1L, extra, 1L));
        var useExtra = recipe("use_extra", target, need(extra, 1), need(raw, 1));
        var impossible = recipe("impossible", target, need(missing, 1));
        var graph = new ImmutableRecipeGraph(Map.of(both, List.of(produceBoth),
                target, List.of(impossible, useExtra)));
        var stock = Map.of(raw, 2L);
        var roots = List.of(need(both, 1), need(target, 1));
        var cache = new DeterministicRecipePlanner.CompilationCache(graph, Map.of(),
                PlanningPreferences.empty(), 1000);
        var cursor = cache.routes(m -> graph.recipesByOutput().getOrDefault(m, List.of()),
                check -> new PureRecipePlanner.SeededReachability(graph, stock, check));
        assertNotNull(cursor.prepare(stock, roots, Set.of(), 10, 1000, () -> {}));
        var result = PureRecipePlanner.resolve(graph, stock, roots, 10, 1000);
        assertTrue(result.feasible());
        assertEquals(List.of(produceBoth.recipeId(), useExtra.recipeId()), result.steps().stream()
                .map(PureRecipePlanner.PlannedStep::recipeId).toList());
        assertTrue(result.remaining().isEmpty());
    }

    private static MaterialRef material(String name) {
        return new MaterialRef(new ResourceLocation("route_test", name), "");
    }

    private static IngredientRef need(MaterialRef material, long count) {
        return new IngredientRef(List.of(material), count);
    }

    private static RecipeNode recipe(String name, MaterialRef output, IngredientRef... inputs) {
        return new RecipeNode(new ResourceLocation("route_test", name), output, 1, List.of(inputs));
    }
}
