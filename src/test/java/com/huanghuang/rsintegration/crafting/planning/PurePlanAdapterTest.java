package com.huanghuang.rsintegration.crafting.planning;

import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.MaterialRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.RecipeNode;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PurePlanAdapterTest {
    @Test
    void preservesPlannerOrderAndBatchCountsWhileDroppingUnknownRecipes() {
        MaterialRef planks = material("planks");
        MaterialRef sticks = material("sticks");
        RecipeNode plankRecipe = recipe("planks", planks, 4);
        RecipeNode stickRecipe = recipe("sticks", sticks, 4);
        ImmutableRecipeGraph graph = new ImmutableRecipeGraph(Map.of(
                planks, List.of(plankRecipe), sticks, List.of(stickRecipe)));
        PureRecipePlanner.Result result = new PureRecipePlanner.Result(true, List.of(
                new PureRecipePlanner.PlannedStep(id("planks"), 2),
                new PureRecipePlanner.PlannedStep(id("missing_projection"), 9),
                new PureRecipePlanner.PlannedStep(id("sticks"), 3)), List.of(), Map.of());

        var adapted = PurePlanAdapter.toResolutionSteps(result, graph);

        assertEquals(List.of(id("planks"), id("sticks")),
                adapted.stream().map(step -> step.recipeId()).toList());
        assertEquals(List.of(2, 3), adapted.stream().map(step -> step.executions()).toList());
    }

    private static RecipeNode recipe(String path, MaterialRef output, int count) {
        return new RecipeNode(id(path), output, count, List.of());
    }

    private static MaterialRef material(String path) {
        return new MaterialRef(id(path), "");
    }

    private static ResourceLocation id(String path) {
        return new ResourceLocation("test", path);
    }
}
