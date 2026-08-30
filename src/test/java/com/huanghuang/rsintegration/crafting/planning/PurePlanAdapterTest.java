package com.huanghuang.rsintegration.crafting.planning;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.IngredientRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.MaterialRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.RecipeNode;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PurePlanAdapterTest {
    @Test
    void preservesTypedIntermediateExecutionRoute() {
        MaterialRef cost = material("cost");
        MaterialRef output = material("output");
        ResourceLocation recipeId = id("typed_intermediate");
        ResourceLocation recipeTypeId = id("typed_recipe_type");
        RecipeNode typed = new RecipeNode(recipeId, output, 1,
                List.of(new IngredientRef(List.of(cost), 1)),
                ModType.FARMINGFORBLOCKHEADS_MARKET.id(), recipeTypeId);
        ImmutableRecipeGraph graph = new ImmutableRecipeGraph(Map.of(output, List.of(typed)));
        PureRecipePlanner.Result plan = new PureRecipePlanner.Result(true,
                List.of(new PureRecipePlanner.PlannedStep(recipeId, 3)), List.of(), Map.of());

        var steps = PurePlanAdapter.toResolutionSteps(plan, graph);

        assertEquals(1, steps.size());
        assertEquals(ModType.FARMINGFORBLOCKHEADS_MARKET, steps.get(0).modType());
        assertEquals(recipeTypeId, steps.get(0).recipeTypeId());
        assertEquals(3, steps.get(0).executions());
    }

    private static MaterialRef material(String path) {
        return new MaterialRef(id(path), "");
    }

    private static ResourceLocation id(String path) {
        return new ResourceLocation("test", path);
    }
}
