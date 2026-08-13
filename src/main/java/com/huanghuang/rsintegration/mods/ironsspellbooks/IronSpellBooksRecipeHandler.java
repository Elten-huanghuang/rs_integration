package com.huanghuang.rsintegration.mods.ironsspellbooks;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.recipe.ModRecipeHandler;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraftforge.common.crafting.StrictNBTIngredient;

import java.util.List;

public final class IronSpellBooksRecipeHandler implements ModRecipeHandler {
    @Override public ModType modType() { return ModType.byId(IronSpellBooksRSModule.SCROLL_FORGE_TYPE); }
    @Override public boolean canHandle(Recipe<?> recipe) { return recipe instanceof IronSpellBooksRecipe; }
    @Override public boolean cacheByRecipeClass() { return false; }
    @Override public ItemStack getResultItem(Recipe<?> recipe, RegistryAccess access) {
        return recipe.getResultItem(access).copy();
    }
    @Override public List<IngredientSpec> getIngredients(Recipe<?> recipe) {
        return ((IronSpellBooksRecipe) recipe).inputs().stream()
                .map(stack -> new IngredientSpec(StrictNBTIngredient.of(stack), stack.getCount()))
                .toList();
    }
}
