package com.huanghuang.rsintegration.crafting.planning;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.crafting.CraftingResolver.ResolutionStep;

import java.util.List;

/** Bridges pure planner output to the existing generic plan representation. */
public final class PurePlanAdapter {
    private PurePlanAdapter() {}

    public static List<ResolutionStep> toResolutionSteps(PureRecipePlanner.Result result,
                                                          ImmutableRecipeGraph graph) {
        return result.steps().stream()
                .filter(step -> graph.recipesById().containsKey(step.recipeId()))
                .map(step -> {
                    ImmutableRecipeGraph.RecipeNode node =
                            graph.recipesById().get(step.recipeId());
                    return new ResolutionStep(step.recipeId(), ModType.byId(node.modTypeId()),
                            node.recipeTypeId(), List.of(), List.of(), false,
                            step.batches(), null, null);
                })
                .toList();
    }
}
