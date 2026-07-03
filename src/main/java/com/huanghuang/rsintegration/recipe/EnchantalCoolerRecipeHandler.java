package com.huanghuang.rsintegration.recipe;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.NonNullList;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;

import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

public final class EnchantalCoolerRecipeHandler implements ModRecipeHandler {

    private static final String RECIPE_CLASS =
            "com.renyigesai.immortalers_delight.recipe.EnchantalCoolerRecipe";

    private static volatile Field inputItemsField;
    private static volatile boolean fieldProbed;

    @Override
    public ModType modType() { return ModType.byId("immortalers_delight"); }

    @Override
    public boolean canHandle(Recipe<?> recipe) {
        return recipe.getClass().getName().equals(RECIPE_CLASS);
    }

    @Override
    public ItemStack getResultItem(Recipe<?> recipe, RegistryAccess access) {
        return ModRecipeHandlers.tryGetResultItem(recipe, access);
    }

    @Nullable
    @Override
    public List<IngredientSpec> getIngredients(Recipe<?> recipe) {
        NonNullList<Ingredient> items = getInputItems(recipe);
        if (items == null || items.isEmpty()) return null;
        List<IngredientSpec> specs = new ArrayList<>(items.size());
        for (Ingredient ing : items) {
            if (!ing.isEmpty()) specs.add(new IngredientSpec(ing, 1));
        }
        return specs.isEmpty() ? null : specs;
    }

    @Nullable
    @SuppressWarnings("unchecked")
    public static NonNullList<Ingredient> getInputItems(Recipe<?> recipe) {
        probeField();
        if (inputItemsField == null) return null;
        try {
            return (NonNullList<Ingredient>) inputItemsField.get(recipe);
        } catch (Exception e) {
            return null;
        }
    }

    private static void probeField() {
        if (fieldProbed) return;
        fieldProbed = true;
        try {
            Class<?> c = Class.forName(RECIPE_CLASS);
            inputItemsField = c.getDeclaredField("inputItems");
            inputItemsField.setAccessible(true);
        } catch (Exception ignored) {}
    }
}
