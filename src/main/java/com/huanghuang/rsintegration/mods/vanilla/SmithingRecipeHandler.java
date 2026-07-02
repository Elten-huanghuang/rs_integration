package com.huanghuang.rsintegration.mods.vanilla;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.recipe.ModRecipeHandler;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.SmithingTransformRecipe;
import net.minecraft.world.item.crafting.SmithingTrimRecipe;
import net.minecraft.world.item.crafting.Recipe;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

public final class SmithingRecipeHandler implements ModRecipeHandler {

    @Override
    public ModType modType() { return ModType.byId("smithing"); }

    @Override
    public boolean canHandle(Recipe<?> recipe) {
        return recipe instanceof SmithingTransformRecipe
                || recipe instanceof SmithingTrimRecipe;
    }

    @Override
    public ItemStack getResultItem(Recipe<?> recipe, RegistryAccess access) {
        return recipe.getResultItem(access);
    }

    @Nullable
    @Override
    public List<IngredientSpec> getIngredients(Recipe<?> recipe) {
        List<Ingredient> ingredients = new ArrayList<>();
        Class<?> clazz = recipe.getClass();
        while (clazz != null && clazz != Object.class) {
            for (java.lang.reflect.Field f : clazz.getDeclaredFields()) {
                if (f.getType() == Ingredient.class) {
                    f.setAccessible(true);
                    try {
                        Ingredient ing = (Ingredient) f.get(recipe);
                        if (ing != null && !ing.isEmpty()) ingredients.add(ing);
                    } catch (Exception ignored) {}
                }
            }
            clazz = clazz.getSuperclass();
        }
        if (ingredients.isEmpty()) return null;
        List<IngredientSpec> specs = new ArrayList<>();
        for (Ingredient ing : ingredients) {
            specs.add(new IngredientSpec(ing, 1));
        }
        return specs.isEmpty() ? null : specs;
    }
}
