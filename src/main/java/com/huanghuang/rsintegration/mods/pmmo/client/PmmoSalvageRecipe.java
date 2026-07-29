package com.huanghuang.rsintegration.mods.pmmo.client;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.Map;

public record PmmoSalvageRecipe(
        ResourceLocation inputId,
        ResourceLocation outputId,
        ItemStack input,
        ItemStack output,
        int salvageMax,
        double baseChance,
        double maxChance,
        Map<String, Double> chancePerLevel,
        Map<String, Integer> levelRequirements,
        Map<String, Long> xpAwards) {

    public PmmoSalvageRecipe {
        input = input.copy();
        output = output.copy();
        chancePerLevel = Map.copyOf(chancePerLevel);
        levelRequirements = Map.copyOf(levelRequirements);
        xpAwards = Map.copyOf(xpAwards);
    }

    public String key() {
        return inputId + "->" + outputId;
    }

    public ResourceLocation recipeId() {
        return com.huanghuang.rsintegration.mods.pmmo.PmmoSalvageRecipeWrapper
                .recipeId(inputId, outputId);
    }
}
