package com.huanghuang.rsintegration.crafting.planning;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.crafting.CraftingResolver.ResolutionStep;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** Bridges pure planner output to the existing generic plan representation. */
public final class PurePlanAdapter {
    private PurePlanAdapter() {}

    private static ItemStack demandedStack(ImmutableRecipeGraph.MaterialRef material) {
        if (material == null) return null;
        ItemStack stack = new ItemStack(BuiltInRegistries.ITEM.get(material.itemId()));
        try {
            if (!material.nbt().isEmpty()) stack.setTag(TagParser.parseTag(material.nbt()));
        } catch (CommandSyntaxException exception) {
            throw new IllegalArgumentException("Invalid planned output state", exception);
        }
        return stack;
    }

    public static List<ResolutionStep> toResolutionSteps(PureRecipePlanner.Result result,
                                                          ImmutableRecipeGraph graph) {
        List<ResolutionStep> projected = new ArrayList<>();
        for (PureRecipePlanner.PlannedStep step : result.steps()) {
            ImmutableRecipeGraph.RecipeNode node = findRecipe(graph, step.recipeId());
            if (node == null) {
                throw new IllegalStateException(
                        "Planned recipe missing from immutable graph: " + step.recipeId());
            }
            long remaining = Math.max(1L, step.batches());
            while (remaining > 0L) {
                int executions = (int) Math.min(Integer.MAX_VALUE, remaining);
                projected.add(new ResolutionStep(
                        step.recipeId(), ModType.byId(node.modTypeId()),
                        node.recipeTypeId(), List.of(), List.of(), false,
                        executions, null, demandedStack(step.demandedOutput()), null,
                        node.magicCosts()));
                remaining -= executions;
            }
        }
        return List.copyOf(projected);
    }

    private static ImmutableRecipeGraph.RecipeNode findRecipe(
            ImmutableRecipeGraph graph, ResourceLocation recipeId) {
        ImmutableRecipeGraph.RecipeNode indexed = graph.recipesById().get(recipeId);
        if (indexed != null) return indexed;
        return graph.recipesByOutput().values().stream()
                .flatMap(List::stream)
                .filter(recipe -> recipe.recipeId().equals(recipeId))
                .findFirst()
                .orElse(null);
    }
}
