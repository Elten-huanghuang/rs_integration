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
                .map(step -> new ResolutionStep(step.recipeId(), ModType.GENERIC,
                        new net.minecraft.resources.ResourceLocation("minecraft", "crafting"),
                        List.of(), List.of(), false, step.batches(), null, null))
                .toList();
    }
}
