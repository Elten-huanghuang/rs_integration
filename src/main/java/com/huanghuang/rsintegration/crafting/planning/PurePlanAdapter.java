package com.huanghuang.rsintegration.crafting.planning;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.crafting.CraftingResolver.ResolutionStep;

import java.util.List;

/** Bridges pure planner output to the existing generic plan representation. */
public final class PurePlanAdapter {
    private PurePlanAdapter() {}

    public static List<ResolutionStep> toResolutionSteps(PureRecipePlanner.Result result,
                                                          ImmutableRecipeGraph graph) {
        return result.steps().stream().map(step -> graph.recipesByOutput().values().stream()
                .flatMap(List::stream)
                .filter(node -> node.recipeId().equals(step.recipeId()))
                .findFirst()
                .map(node -> new ResolutionStep(node.recipeId(), ModType.GENERIC,
                        new net.minecraft.resources.ResourceLocation("minecraft", "crafting"),
                        List.of(), List.of(), false, step.batches(), null, null))
                .orElse(null)).filter(java.util.Objects::nonNull).toList();
    }
}
