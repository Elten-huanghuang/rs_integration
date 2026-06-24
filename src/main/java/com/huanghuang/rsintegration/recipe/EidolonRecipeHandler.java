package com.huanghuang.rsintegration.recipe;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.batch.ModType;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.util.Reflect;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;

import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

final class EidolonRecipeHandler implements ModRecipeHandler {

    @Override
    public ModType modType() { return ModType.EIDOLON; }

    @Override
    public boolean canHandle(Recipe<?> recipe) {
        return recipe.getClass().getName().startsWith("elucent.eidolon.");
    }

    @Override
    public ItemStack getResultItem(Recipe<?> recipe, RegistryAccess access) {
        return ModRecipeHandlers.tryGetResultItem(recipe, access);
    }

    @Nullable
    @Override
    @SuppressWarnings("unchecked")
    public List<IngredientSpec> getIngredients(Recipe<?> recipe) {
        var stepsOpt = Reflect.invoke(recipe, "getSteps");
        if (stepsOpt.isEmpty()) return null;
        List<?> steps = (List<?>) stepsOpt.get();
        if (steps == null || steps.isEmpty()) return null;

        List<IngredientSpec> result = new ArrayList<>();
        for (Object step : steps) {
            try {
                Field matchesField = step.getClass().getField("matches");
                List<Ingredient> matches = (List<Ingredient>) matchesField.get(step);
                if (matches != null) {
                    for (Ingredient ing : matches) {
                        if (!ing.isEmpty()) result.add(new IngredientSpec(ing, 1));
                    }
                }
            } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI] Reflection probe failed", e); }
        }
        return result.isEmpty() ? null : result;
    }
}
