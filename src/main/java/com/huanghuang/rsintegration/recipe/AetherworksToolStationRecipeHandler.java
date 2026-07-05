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

public final class AetherworksToolStationRecipeHandler implements ModRecipeHandler {

    @Override
    public ModType modType() { return ModType.byId(ModIds.ID_AETHERWORKS_TOOL_STATION); }

    @Override
    public boolean canHandle(Recipe<?> recipe) {
        for (Class<?> iface : recipe.getClass().getInterfaces()) {
            if ("net.sirplop.aetherworks.recipe.IToolStationRecipe".equals(iface.getName())) {
                return true;
            }
        }
        return false;
    }

    @Override
    public ItemStack getResultItem(Recipe<?> recipe, RegistryAccess access) {
        try {
            java.lang.reflect.Method m = recipe.getClass().getMethod("getResultItem");
            Object r = m.invoke(recipe);
            if (r instanceof ItemStack s && !s.isEmpty()) return s;
        } catch (Exception ignored) {}
        // Field fallback
        try {
            java.lang.reflect.Field f = recipe.getClass().getDeclaredField("output");
            f.setAccessible(true);
            Object v = f.get(recipe);
            if (v instanceof ItemStack s && !s.isEmpty()) return s;
        } catch (Exception ignored) {}
        return ItemStack.EMPTY;
    }

    @Nullable
    @Override
    public List<IngredientSpec> getIngredients(Recipe<?> recipe) {
        try {
            java.lang.reflect.Method m = recipe.getClass().getMethod("getDisplayInputs");
            @SuppressWarnings("unchecked")
            List<Ingredient> inputs = (List<Ingredient>) m.invoke(recipe);
            if (inputs != null) {
                List<IngredientSpec> specs = new ArrayList<>();
                for (Ingredient ing : inputs) {
                    if (!ing.isEmpty()) specs.add(new IngredientSpec(ing, 1));
                }
                if (!specs.isEmpty()) return specs;
            }
        } catch (Exception ignored) {}
        return null;
    }
}
