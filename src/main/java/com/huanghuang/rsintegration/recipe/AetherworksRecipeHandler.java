package com.huanghuang.rsintegration.recipe;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.util.ModIds;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

public final class AetherworksRecipeHandler implements ModRecipeHandler {

    @Override
    public ModType modType() { return ModType.byId(ModIds.ID_AETHERWORKS_ANVIL); }

    @Override
    public boolean canHandle(Recipe<?> recipe) {
        return recipe.getClass().getName().startsWith("net.sirplop.aetherworks.");
    }

    @Override
    public ItemStack getResultItem(Recipe<?> recipe, RegistryAccess access) {
        // Probe getResult/getOutput/getOutputCopy/getAssembledItem first.
        // Fall back to getResultItem last — some recipes (e.g. Goety
        // Revelation cross-mod anvil recipes) only expose output through
        // the deprecated 0-arg getResultItem() and don't have other methods.
        for (String name : new String[]{"getResult", "getOutput", "getOutputCopy", "getAssembledItem", "getResultItem"}) {
            for (java.lang.reflect.Method m : recipe.getClass().getMethods()) {
                if (!m.getName().equals(name)) continue;
                if (!ItemStack.class.isAssignableFrom(m.getReturnType())) continue;
                if (m.getParameterCount() == 1) {
                    try {
                        Object r = m.invoke(recipe, access);
                        if (r instanceof ItemStack s && !s.isEmpty()) return s;
                    } catch (Exception ignored) {}
                } else if (m.getParameterCount() == 0) {
                    try {
                        Object r = m.invoke(recipe);
                        if (r instanceof ItemStack s && !s.isEmpty()) return s;
                    } catch (Exception ignored) {}
                }
            }
        }
        // Field fallback — some cross-mod recipes store output directly
        for (String fieldName : new String[]{"output", "result"}) {
            try {
                java.lang.reflect.Field f = recipe.getClass().getDeclaredField(fieldName);
                f.setAccessible(true);
                Object v = f.get(recipe);
                if (v instanceof ItemStack s && !s.isEmpty()) return s;
            } catch (Exception ignored) {}
        }
        return ItemStack.EMPTY;
    }

    @Nullable
    @Override
    public List<IngredientSpec> getIngredients(Recipe<?> recipe) {
        List<IngredientSpec> specs = new ArrayList<>();

        try {
            java.lang.reflect.Method m = recipe.getClass().getMethod("getDisplayInput");
            Object result = m.invoke(recipe);
            if (result instanceof Ingredient ing && !ing.isEmpty()) {
                specs.add(new IngredientSpec(ing, 1));
            }
        } catch (Exception ignored) {}

        try {
            java.lang.reflect.Method m = recipe.getClass().getMethod("getAddition");
            Object result = m.invoke(recipe);
            if (result instanceof Ingredient ing && !ing.isEmpty()) {
                specs.add(new IngredientSpec(ing, 1));
            }
        } catch (Exception ignored) {}

        return specs.isEmpty() ? null : specs;
    }
}
