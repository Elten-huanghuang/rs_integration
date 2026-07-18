package com.huanghuang.rsintegration.mods.distantworlds;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.recipe.ModRecipeHandler;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;

import javax.annotation.Nullable;
import java.util.List;

public final class LithumAltarRecipeHandler implements ModRecipeHandler {
    @Override public ModType modType() { return ModType.byId(LithumAltarRecipeResolver.TYPE_ID); }
    @Override public boolean canHandle(Recipe<?> recipe) { return recipe instanceof LithumAltarRecipeWrapper; }

    @Override
    public ItemStack getResultItem(Recipe<?> recipe, RegistryAccess access) {
        return recipe instanceof LithumAltarRecipeWrapper wrapper
                ? wrapper.definition().output().copy() : ItemStack.EMPTY;
    }

    @Nullable
    @Override
    public List<IngredientSpec> getIngredients(Recipe<?> recipe) {
        return recipe instanceof LithumAltarRecipeWrapper wrapper
                ? wrapper.definition().allMaterials() : null;
    }
}
