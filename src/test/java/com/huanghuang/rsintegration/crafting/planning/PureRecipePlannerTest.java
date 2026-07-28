package com.huanghuang.rsintegration.crafting.planning;

import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.IngredientRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.MaterialRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.RecipeNode;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
