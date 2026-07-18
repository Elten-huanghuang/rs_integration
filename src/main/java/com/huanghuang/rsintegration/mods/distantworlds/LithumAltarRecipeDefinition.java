package com.huanghuang.rsintegration.mods.distantworlds;

import com.huanghuang.rsintegration.crafting.IngredientSpec;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/** Immutable server-side definition for one Distant Worlds Firon altar recipe. */
public record LithumAltarRecipeDefinition(
        String currentRecipe,
        IngredientSpec coreInput,
        List<IngredientSpec> pedestalInputs,
        int requiredEmptyPedestals,
        ItemStack output,
        int baseMaxEnergy,
        int baseRecovery
) {
    public LithumAltarRecipeDefinition {
        pedestalInputs = List.copyOf(pedestalInputs);
        output = output.copy();
    }

    public List<IngredientSpec> allMaterials() {
        java.util.ArrayList<IngredientSpec> result = new java.util.ArrayList<>();
        if (coreInput != null && !coreInput.isEmpty()) result.add(coreInput);
        result.addAll(pedestalInputs);
        return List.copyOf(result);
    }
}
