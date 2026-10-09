package com.huanghuang.rsintegration.mods.embers;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.mods.ironsspellbooks.InkFluidSupport;
import com.huanghuang.rsintegration.recipe.ModRecipeHandler;
import com.huanghuang.rsintegration.util.ModIds;
import com.rekindled.embers.recipe.IMeltingRecipe;
import com.rekindled.embers.recipe.MeltingRecipe;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;

import java.util.List;

public final class EmbersMeltingRecipeHandler implements ModRecipeHandler {
    @Override public ModType modType() { return ModType.byId(ModIds.ID_EMBERS_MELTER); }
    @Override public boolean canHandle(Recipe<?> recipe) { return recipe instanceof IMeltingRecipe; }
    @Override public boolean preferHandlerIngredients() { return true; }
    @Override public boolean isCompatibleBinding(Recipe<?> recipe, String blockKey) {
        return blockKey != null && blockKey.contains(ModIds.ID_EMBERS_MELTER);
    }
    @Override public ItemStack getResultItem(Recipe<?> recipe, RegistryAccess access) {
        return InkFluidSupport.token(((IMeltingRecipe) recipe).getDisplayOutput());
    }
    @Override public List<IngredientSpec> getIngredients(Recipe<?> recipe) {
        Ingredient input = ((IMeltingRecipe) recipe).getDisplayInput();
        return input == null || input.isEmpty() ? null : List.of(new IngredientSpec(input, 1));
    }
    @Override public boolean supportsBackgroundPlanning(Recipe<?> recipe) {
        return recipe.getClass() == MeltingRecipe.class
                && ModRecipeHandler.super.supportsBackgroundPlanning(recipe);
    }
    @Override public boolean supportsIntermediateProjection(Recipe<?> recipe) {
        return supportsBackgroundPlanning(recipe);
    }
}
