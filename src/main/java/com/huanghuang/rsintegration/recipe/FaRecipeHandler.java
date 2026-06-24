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
import java.util.Optional;

final class FaRecipeHandler implements ModRecipeHandler {

    @Override
    public ModType modType() { return ModType.FORBIDDEN_ARCANUS; }

    @Override
    public boolean canHandle(Recipe<?> recipe) {
        return recipe.getClass().getName().startsWith("com.stal111.forbidden_arcanus.");
    }

    @Override
    public ItemStack getResultItem(Recipe<?> recipe, RegistryAccess access) {
        return ModRecipeHandlers.tryGetResultItem(recipe, access);
    }

    @Nullable
    @Override
    @SuppressWarnings("unchecked")
    public List<IngredientSpec> getIngredients(Recipe<?> recipe) {
        List<IngredientSpec> specs = new ArrayList<>();

        // mainIngredient field
        Reflect.findField(recipe.getClass(), "mainIngredient").ifPresent(f -> {
            if (!Ingredient.class.isAssignableFrom(f.getType())) return;
            f.setAccessible(true);
            try {
                Ingredient ing = (Ingredient) f.get(recipe);
                if (ing != null && !ing.isEmpty()) specs.add(new IngredientSpec(ing, 1));
            } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI] Reflection probe failed", e); }
        });

        // inputs field — list of objects with ingredient + amount
        Reflect.findField(recipe.getClass(), "inputs").ifPresent(f -> {
            if (!List.class.isAssignableFrom(f.getType())) return;
            f.setAccessible(true);
            try {
                List<?> inputs = (List<?>) f.get(recipe);
                if (inputs == null) return;
                for (Object ri : inputs) {
                    Optional<Field> ingField = Reflect.findField(ri.getClass(), "ingredient");
                    Optional<Field> amtField = Reflect.findField(ri.getClass(), "amount");
                    if (ingField.isEmpty() || !Ingredient.class.isAssignableFrom(ingField.get().getType())) continue;
                    ingField.get().setAccessible(true);
                    Ingredient ing = (Ingredient) ingField.get().get(ri);
                    if (ing == null || ing.isEmpty()) continue;
                    int amt = 1;
                    if (amtField.isPresent()) {
                        amtField.get().setAccessible(true);
                        amt = Math.max(1, amtField.get().getInt(ri));
                    }
                    specs.add(new IngredientSpec(ing, amt));
                }
            } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI] Reflection probe failed", e); }
        });

        return specs.isEmpty() ? null : specs;
    }
}
