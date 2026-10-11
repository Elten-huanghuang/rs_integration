package com.huanghuang.rsintegration.mods.embers;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.mods.ironsspellbooks.InkFluidSupport;
import com.huanghuang.rsintegration.recipe.ModRecipeHandler;
import com.huanghuang.rsintegration.util.ModIds;
import com.rekindled.embers.recipe.BoilingRecipe;
import com.rekindled.embers.recipe.IBoilingRecipe;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;

import java.util.List;

/** 沸腾配方的液体原料和气体产物按实际毫桶数量进入递归账本。 */
public final class EmbersBoilingRecipeHandler implements ModRecipeHandler {
    @Override public ModType modType() { return ModType.byId(ModIds.ID_EMBERS_MINI_BOILER); }
    @Override public boolean canHandle(Recipe<?> recipe) { return recipe instanceof IBoilingRecipe; }
    @Override public boolean preferHandlerIngredients() { return true; }
    @Override public boolean isCompatibleBinding(Recipe<?> recipe, String blockKey) {
        return blockKey != null && (blockKey.startsWith(ModIds.ID_EMBERS_MINI_BOILER + "||")
                || blockKey.endsWith(".mini_boiler"));
    }
    @Override public ItemStack getResultItem(Recipe<?> recipe, RegistryAccess access) {
        return InkFluidSupport.token(((IBoilingRecipe) recipe).getDisplayOutput());
    }
    @Override public List<IngredientSpec> getIngredients(Recipe<?> recipe) {
        IngredientSpec input = EmbersFluidIngredients.spec(((IBoilingRecipe) recipe).getDisplayInput());
        return input == null ? null : List.of(input);
    }
    @Override public boolean supportsBackgroundPlanning(Recipe<?> recipe) {
        return recipe.getClass() == BoilingRecipe.class
                && ModRecipeHandler.super.supportsBackgroundPlanning(recipe);
    }
    @Override public boolean supportsIntermediateProjection(Recipe<?> recipe) {
        return supportsBackgroundPlanning(recipe);
    }
}
