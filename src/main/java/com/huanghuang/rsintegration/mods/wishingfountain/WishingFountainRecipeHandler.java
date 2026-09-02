package com.huanghuang.rsintegration.mods.wishingfountain;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.recipe.ModRecipeHandler;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;

import java.util.ArrayList;
import java.util.List;

public final class WishingFountainRecipeHandler implements ModRecipeHandler {
    public static final String RECIPE_CLASS =
            "io.github.poisonsheep.wishingfountain.recipe.WFRecipe";

    @Override
    public ModType modType() {
        return ModType.byId(WishingFountainRSModule.TYPE_ID);
    }

    @Override
    public boolean canHandle(Recipe<?> recipe) {
        return recipe != null && RECIPE_CLASS.equals(recipe.getClass().getName());
    }

    @Override
    public ItemStack getResultItem(Recipe<?> recipe, RegistryAccess access) {
        ItemStack result = recipe.getResultItem(access);
        return result == null ? ItemStack.EMPTY : result.copy();
    }

    @Override
    public List<IngredientSpec> getIngredients(Recipe<?> recipe) {
        List<IngredientSpec> result = new ArrayList<>();
        for (Ingredient ingredient : recipe.getIngredients()) {
            if (!ingredient.isEmpty()) result.add(new IngredientSpec(ingredient, 1));
        }
        return result;
    }

    @Override
    public boolean indexPrimaryOutput(Recipe<?> recipe) {
        try {
            Object wishType = recipe.getClass().getMethod("getMapType").invoke(recipe);
            return !"weather".equals(wishType);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return false;
        }
    }
}
