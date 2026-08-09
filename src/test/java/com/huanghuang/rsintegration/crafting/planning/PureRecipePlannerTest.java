package com.huanghuang.rsintegration.crafting.planning;

import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.IngredientRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.MaterialRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.RecipeNode;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
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
    void recursivelyAmplifiesASeedUsedByItsOwnRecipe() {
        MaterialRef template = material("template");
        MaterialRef stone = material("stone");
        RecipeNode duplicate = recipe("duplicate_template", template, 2,
                ingredient(template, 1), ingredient(stone, 7));
        ImmutableRecipeGraph graph = new ImmutableRecipeGraph(Map.of(
                template, List.of(duplicate)));

        PureRecipePlanner.Result result = PureRecipePlanner.resolve(graph,
                Map.of(template, 1, stone, 49), List.of(ingredient(template, 8)), 20);

        assertTrue(result.feasible());
        assertEquals(List.of(
                new PureRecipePlanner.PlannedStep(id("duplicate_template"), 1),
                new PureRecipePlanner.PlannedStep(id("duplicate_template"), 2),
                new PureRecipePlanner.PlannedStep(id("duplicate_template"), 4)),
                result.steps());
        assertTrue(result.remaining().isEmpty());
    }

    @Test
    void amplificationRecipeStillRequiresASeed() {
        MaterialRef template = material("template");
        MaterialRef stone = material("stone");
        RecipeNode duplicate = recipe("duplicate_template", template, 2,
                ingredient(template, 1), ingredient(stone, 7));
        ImmutableRecipeGraph graph = new ImmutableRecipeGraph(Map.of(
                template, List.of(duplicate)));

        PureRecipePlanner.Result result = PureRecipePlanner.resolve(graph,
                Map.of(stone, 49), List.of(ingredient(template, 8)), 20);

        assertFalse(result.feasible());
        assertEquals(PureRecipePlanner.Status.UNRESOLVABLE, result.status());
        assertEquals(Map.of(stone, 49), result.remaining());
    }

    @Test
    void selfAmplifyingRecipeWorksAsAnIntermediateStep() {
        MaterialRef template = material("template");
        MaterialRef stone = material("stone");
        MaterialRef artifact = material("artifact");
        RecipeNode duplicate = recipe("duplicate_template", template, 2,
                ingredient(template, 1), ingredient(stone, 7));
        RecipeNode assemble = recipe("assemble_artifact", artifact, 1,
                ingredient(template, 8));
        ImmutableRecipeGraph graph = new ImmutableRecipeGraph(Map.of(
                template, List.of(duplicate), artifact, List.of(assemble)));

        PureRecipePlanner.Result result = PureRecipePlanner.resolve(graph,
                Map.of(template, 1, stone, 49), List.of(ingredient(artifact, 1)), 20);

        assertTrue(result.feasible());
        assertEquals(List.of(
                new PureRecipePlanner.PlannedStep(id("duplicate_template"), 1),
                new PureRecipePlanner.PlannedStep(id("duplicate_template"), 2),
                new PureRecipePlanner.PlannedStep(id("duplicate_template"), 4),
                new PureRecipePlanner.PlannedStep(id("assemble_artifact"), 1)),
                result.steps());
        assertTrue(result.remaining().isEmpty());
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
    void aggregatesTagVariantsWhilePreservingLaterExactDemand() {
        MaterialRef whiteWool = material("white_wool");
        MaterialRef blackWool = material("black_wool");
        IngredientRef anyWool = new IngredientRef(List.of(whiteWool, blackWool), 6);

        PureRecipePlanner.Result result = PureRecipePlanner.resolve(
                new ImmutableRecipeGraph(Map.of()), Map.of(whiteWool, 4, blackWool, 4),
                List.of(anyWool, ingredient(whiteWool, 2)), 20);

        assertTrue(result.feasible());
        assertEquals(PureRecipePlanner.Status.SUCCESS, result.status());
        assertTrue(result.remaining().isEmpty());
    }

    @Test
    void largeTagUsesAggregateStockWithoutRecipeSearch() {
        List<MaterialRef> fuels = new ArrayList<>();
        Map<MaterialRef, Integer> stock = new LinkedHashMap<>();
        for (int i = 0; i < 128; i++) {
            MaterialRef fuel = material("fuel_" + i);
            fuels.add(fuel);
            stock.put(fuel, 1);
        }

        PureRecipePlanner.Result result = PureRecipePlanner.resolve(
                new ImmutableRecipeGraph(Map.of()), stock,
                List.of(new IngredientRef(fuels, 96)), 20);

        assertTrue(result.feasible());
        assertEquals(PureRecipePlanner.Status.SUCCESS, result.status());
        assertTrue(result.expandedStates() <= 2);
    }

    @Test
    void quartzConversionsBacktrackInsteadOfReportingChiseledQuartzMissing() {
        MaterialRef quartz = material("quartz_block");
        MaterialRef slab = material("quartz_slab");
        MaterialRef chiseled = material("chiseled_quartz_block");
        MaterialRef stairs = material("quartz_stairs");

        ImmutableRecipeGraph graph = new ImmutableRecipeGraph(Map.of(
                slab, List.of(
                        recipe("slabs_from_chiseled", slab, 2, ingredient(chiseled, 1)),
                        recipe("slabs_from_quartz", slab, 2, ingredient(quartz, 1))),
                chiseled, List.of(recipe("chiseled_from_slabs", chiseled, 1,
                        ingredient(slab, 2))),
                stairs, List.of(recipe("stairs_from_chiseled", stairs, 4,
                        ingredient(chiseled, 6)))));

        PureRecipePlanner.Result result = PureRecipePlanner.resolve(graph, Map.of(quartz, 8),
                List.of(ingredient(slab, 1), ingredient(stairs, 2), ingredient(chiseled, 1)), 20);

        assertTrue(result.feasible());
        assertEquals(PureRecipePlanner.Status.SUCCESS, result.status());
        assertTrue(result.backtracks() > 0);
        assertTrue(result.steps().stream().anyMatch(step ->
                step.recipeId().equals(id("slabs_from_quartz"))));
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
    void expiredDeadlineProducesUnknownTimeLimit() {
        PureRecipePlanner.Result result = PureRecipePlanner.resolve(
                new ImmutableRecipeGraph(Map.of()), Map.of(), List.of(ingredient(LOG, 1)),
                20, 100, 100, System.nanoTime() - 1L);

        assertFalse(result.feasible());
        assertEquals(PureRecipePlanner.Feasibility.UNKNOWN, result.feasibility());
        assertEquals(PureRecipePlanner.Status.TIME_LIMIT, result.status());
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
