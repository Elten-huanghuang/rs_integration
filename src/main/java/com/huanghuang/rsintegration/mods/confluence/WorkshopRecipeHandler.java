package com.huanghuang.rsintegration.mods.confluence;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.recipe.ModRecipeHandler;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Handles TerraCurio {@code AbstractAmountRecipe} subclasses
 * ({@code WorkshopRecipe}, etc.) for recipe indexing and recursive crafting.
 */
public final class WorkshopRecipeHandler implements ModRecipeHandler {

    @Override
    public ModType modType() { return ModType.byId("confluence"); }

    @Override
    public boolean canHandle(Recipe<?> recipe) {
        return recipe.getClass().getName().startsWith("org.confluence.mod.recipe.");
    }

    @Override
    public ItemStack getResultItem(Recipe<?> recipe, RegistryAccess access) {
        return recipe.getResultItem(access);
    }

    @Nullable
    @Override
    public List<IngredientSpec> getIngredients(Recipe<?> recipe) {
        List<Ingredient> ingredients = recipe.getIngredients();
        if (ingredients.isEmpty()) return null;
        List<IngredientSpec> specs = new ArrayList<>();
        for (Ingredient ing : ingredients) {
            if (ing.isEmpty()) continue;
            int count = extractCount(ing);
            specs.add(new IngredientSpec(ing, count));
        }
        return specs.isEmpty() ? null : specs;
    }

    // AmountIngredient extends Ingredient and has a getCount() method.
    // Use reflection so we don't need TerraCurio on the compile classpath.
    private static int extractCount(Ingredient ing) {
        try {
            Class<?> clazz = ing.getClass();
            if (clazz.getName().equals("org.confluence.mod.recipe.AmountIngredient")) {
                java.lang.reflect.Method m = clazz.getMethod("getCount");
                Object val = m.invoke(ing);
                if (val instanceof Integer count && count > 0) return count;
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI-Confluence] AmountIngredient.getCount() failed", e);
        }
        return 1;
    }
}
