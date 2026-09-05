package com.huanghuang.rsintegration.crafting.planning;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.crafting.CraftingResolver.ResolutionStep;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.TagParser;
import net.minecraft.world.item.ItemStack;

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
        return result.steps().stream()
                .filter(step -> graph.recipesById().containsKey(step.recipeId()))
                .map(step -> {
                    ImmutableRecipeGraph.RecipeNode node =
                            graph.recipesById().get(step.recipeId());
                    return new ResolutionStep(step.recipeId(), ModType.byId(node.modTypeId()),
                            node.recipeTypeId(), List.of(), List.of(), false,
                            step.batches(), null, demandedStack(step.demandedOutput()));
                })
                .toList();
    }
}
