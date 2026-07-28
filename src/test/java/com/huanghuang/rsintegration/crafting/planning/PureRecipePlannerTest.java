package com.huanghuang.rsintegration.crafting.planning;

import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.IngredientRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.MaterialRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.RecipeNode;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PureRecipePlannerTest {
    private static final MaterialRef LOG = material("log");
    private static final MaterialRef PLANK = material("plank");
    private static final MaterialRef STICK = material("stick");

    @Test
    void recursivelyPlansAndScalesBatchesWithoutMinecraftObjects() {
        RecipeNode planks = recipe("planks", PLANK, 4, ingredient(LOG, 1));
        RecipeNode sticks = recipe("sticks", STICK, 4, ingredient(PLANK, 2));
        ImmutableRecipeGraph graph = new ImmutableRecipeGraph(Map.of(
                PLANK, List.of(planks), STICK, List.of(sticks)));

        PureRecipePlanner.Result result = PureRecipePlanner.resolve(graph, Map.of(LOG, 2),
                List.of(ingredient(STICK, 8)), 20);

        assertTrue(result.feasible());
        assertEquals(List.of(new PureRecipePlanner.PlannedStep(id("planks"), 1),
                new PureRecipePlanner.PlannedStep(id("sticks"), 2)), result.steps());
    }

    @Test
    void cycleTerminatesAndReportsMissing() {
        RecipeNode logFromPlank = recipe("log", LOG, 1, ingredient(PLANK, 1));
        RecipeNode plankFromLog = recipe("plank", PLANK, 1, ingredient(LOG, 1));
        ImmutableRecipeGraph graph = new ImmutableRecipeGraph(Map.of(
                LOG, List.of(logFromPlank), PLANK, List.of(plankFromLog)));
        PureRecipePlanner.Result result = PureRecipePlanner.resolve(graph, Map.of(),
                List.of(ingredient(LOG, 1)), 20);
        assertFalse(result.feasible());
        assertEquals(1, result.missing().size());
        assertEquals(PureRecipePlanner.Status.UNRESOLVABLE, result.status());
    }

    @Test
    void backtracksAcrossEarlierRootWhenItsFirstRecipeConsumesLaterMaterial() {
        MaterialRef ore = material("ore");
        MaterialRef fuel = material("fuel");
        MaterialRef gear = material("gear");
        RecipeNode gearFromFuel = recipe("gear_from_fuel", gear, 1, ingredient(fuel, 1));
        RecipeNode gearFromOre = recipe("gear_from_ore", gear, 1, ingredient(ore, 1));
        ImmutableRecipeGraph graph = new ImmutableRecipeGraph(Map.of(
                gear, List.of(gearFromFuel, gearFromOre)));

        PureRecipePlanner.Result result = PureRecipePlanner.resolve(graph,
                Map.of(ore, 1, fuel, 1),
                List.of(ingredient(gear, 1), ingredient(fuel, 1)), 20);

        assertTrue(result.feasible());
        assertEquals(PureRecipePlanner.Status.SUCCESS, result.status());
        assertEquals(List.of(new PureRecipePlanner.PlannedStep(id("gear_from_ore"), 1)),
                result.steps());
        assertTrue(result.backtracks() > 0);
    }

    @Test
    void backtracksAcrossStockedIngredientAlternatives() {
        MaterialRef copper = material("copper");
        MaterialRef tin = material("tin");
        IngredientRef eitherMetal = new IngredientRef(List.of(copper, tin), 1);

        PureRecipePlanner.Result result = PureRecipePlanner.resolve(
                new ImmutableRecipeGraph(Map.of()), Map.of(copper, 1, tin, 1),
                List.of(eitherMetal, ingredient(copper, 1)), 20);

        assertTrue(result.feasible());
        assertEquals(0, result.remaining().size());
        assertTrue(result.backtracks() > 0);
    }

    @Test
    void searchBudgetProducesExplicitNonSuccessStatus() {
        MaterialRef target = material("target");
        RecipeNode impossibleA = recipe("impossible_a", target, 1, ingredient(LOG, 1));
        RecipeNode impossibleB = recipe("impossible_b", target, 1, ingredient(PLANK, 1));
        ImmutableRecipeGraph graph = new ImmutableRecipeGraph(Map.of(
                target, List.of(impossibleA, impossibleB)));

        PureRecipePlanner.Result result = PureRecipePlanner.resolve(
                graph, Map.of(), List.of(ingredient(target, 1)), 20, 1);

        assertFalse(result.feasible());
        assertEquals(PureRecipePlanner.Status.SEARCH_LIMIT, result.status());
        assertTrue(result.expandedStates() > 1);
        assertTrue(result.steps().isEmpty());
        assertTrue(result.remaining().isEmpty());
    }

    @Test
    void stepBudgetProducesExplicitNonSuccessStatus() {
        MaterialRef board = material("board");
        MaterialRef handle = material("handle");
        ImmutableRecipeGraph graph = new ImmutableRecipeGraph(Map.of(
                board, List.of(recipe("board", board, 1, ingredient(LOG, 1))),
                handle, List.of(recipe("handle", handle, 1, ingredient(board, 1)))));

        PureRecipePlanner.Result result = PureRecipePlanner.resolve(
                graph, Map.of(LOG, 1), List.of(ingredient(handle, 1)), 1, 100);

        assertFalse(result.feasible());
        assertEquals(PureRecipePlanner.Status.STEP_LIMIT, result.status());
        assertTrue(result.steps().isEmpty());
        assertEquals(Map.of(LOG, 1), result.remaining());
    }

    @Test
    void interruptedPlanningStopsCooperatively() {
        Thread.currentThread().interrupt();
        try {
            assertThrows(CancellationException.class, () -> PureRecipePlanner.resolve(
                    new ImmutableRecipeGraph(Map.of()), Map.of(), List.of(ingredient(LOG, 1)), 20));
        } finally {
            Thread.interrupted();
        }
    }

    private static RecipeNode recipe(String id, MaterialRef output, int count, IngredientRef... inputs) {
        return new RecipeNode(id(id), output, count, List.of(inputs));
    }

    private static IngredientRef ingredient(MaterialRef material, int count) {
        return new IngredientRef(List.of(material), count);
    }

    private static MaterialRef material(String path) {
        return new MaterialRef(id(path), "");
    }

    private static ResourceLocation id(String path) {
        return new ResourceLocation("test", path);
    }
}
