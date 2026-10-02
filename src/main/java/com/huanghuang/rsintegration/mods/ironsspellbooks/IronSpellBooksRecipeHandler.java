package com.huanghuang.rsintegration.mods.ironsspellbooks;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.recipe.ModRecipeHandler;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import java.util.ArrayList;
import net.minecraft.world.item.crafting.Ingredient;

import java.util.List;

public final class IronSpellBooksRecipeHandler implements ModRecipeHandler {
    @Override public ModType modType() { return ModType.byId(IronSpellBooksRSModule.SCROLL_FORGE_TYPE); }
    @Override public boolean canHandle(Recipe<?> recipe) { return recipe instanceof IronSpellBooksRecipe; }
    @Override public boolean cacheByRecipeClass() { return false; }
    @Override public boolean hasDeterministicPrimaryOutput(Recipe<?> recipe) {
        return !(recipe instanceof IronSpellBooksRecipe ironRecipe) || !ironRecipe.isScrollRecycling();
    }
    @Override public boolean supportsTargetedProduction(Recipe<?> recipe) {
        return recipe instanceof IronSpellBooksRecipe ironRecipe && ironRecipe.isScrollRecycling();
    }
    @Override public ItemStack getResultItem(Recipe<?> recipe, RegistryAccess access) {
        return recipe.getResultItem(access).copy();
    }
    @Override public List<IngredientSpec> getIngredients(Recipe<?> recipe) {
        IronSpellBooksRecipe ironRecipe = (IronSpellBooksRecipe) recipe;
        List<ItemStack> displays = ironRecipe.inputs();
        List<Ingredient> ingredients =
                ironRecipe.inputIngredients();
        ArrayList<IngredientSpec> result = new ArrayList<>(ingredients.size());
        for (int i = 0; i < ingredients.size(); i++) {
            result.add(new IngredientSpec(ingredients.get(i), displays.get(i).getCount()));
        }
        return List.copyOf(result);
    }
}
