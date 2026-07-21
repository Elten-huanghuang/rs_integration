package com.huanghuang.rsintegration.recipe;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

public final class CursedInfuserRecipeHandler extends AbstractRecipeHandler {
    private static final String RECIPE_CLASS =
            "com.Polarice3.Goety.common.crafting.CursedInfuserRecipes";

    @Override
    public ModType modType() {
        return ModType.byId("goety_cursed_infuser");
    }

    @Override
    public boolean canHandle(Recipe<?> recipe) {
        if (!RECIPE_CLASS.equals(recipe.getClass().getName())) return false;
        try {
            return !(boolean) recipe.getClass().getMethod("isGrim").invoke(recipe);
        } catch (ReflectiveOperationException ignored) {
            return false;
        }
    }

    @Override
    public ItemStack getResultItem(Recipe<?> recipe, RegistryAccess access) {
        return recipe.getResultItem(access).copy();
    }

    @Nullable
    @Override
    public List<IngredientSpec> getIngredients(Recipe<?> recipe) {
        List<IngredientSpec> result = new ArrayList<>();
        for (Ingredient ingredient : recipe.getIngredients()) {
            if (!ingredient.isEmpty()) result.add(new IngredientSpec(ingredient, 1));
        }
        return result.isEmpty() ? null : result;
    }
}
