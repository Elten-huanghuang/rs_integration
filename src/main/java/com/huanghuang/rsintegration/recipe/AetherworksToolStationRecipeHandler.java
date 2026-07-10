package com.huanghuang.rsintegration.recipe;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.util.ModIds;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

public final class AetherworksToolStationRecipeHandler extends AbstractRecipeHandler {

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
        // Probe multiple method names with both 1-arg and 0-arg variants,
        // matching the Anvil handler's thorough approach.  The deprecated
        // no-arg getResultItem() rarely works in 1.20.1 — the canonical
        // call is getResultItem(RegistryAccess).
        for (String name : new String[]{"getResultItem", "getResult", "getOutput", "getOutputCopy", "getAssembledItem"}) {
            for (java.lang.reflect.Method m : recipe.getClass().getMethods()) {
                if (!m.getName().equals(name)) continue;
                if (!ItemStack.class.isAssignableFrom(m.getReturnType())) continue;
                if (m.getParameterCount() == 1) {
                    try {
                        Object r = m.invoke(recipe, access);
                        if (r instanceof ItemStack s && !s.isEmpty()) return s;
                    } catch (Exception e) {
                        RSIntegrationMod.LOGGER.debug("[RSI-Recipe] reflection probe failed", e);
                    }
                } else if (m.getParameterCount() == 0) {
                    try {
                        Object r = m.invoke(recipe);
                        if (r instanceof ItemStack s && !s.isEmpty()) return s;
                    } catch (Exception e) {
                        RSIntegrationMod.LOGGER.debug("[RSI-Recipe] reflection probe failed", e);
                    }
                }
            }
        }
        // Field fallback — probe multiple common field names
        for (String fieldName : new String[]{"output", "result"}) {
            try {
                java.lang.reflect.Field f = recipe.getClass().getDeclaredField(fieldName);
                f.setAccessible(true);
                Object v = f.get(recipe);
                if (v instanceof ItemStack s && !s.isEmpty()) return s;
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.debug("[RSI-Recipe] reflection probe failed", e);
            }
        }
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
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI-Recipe] reflection probe failed", e);
        }
        return null;
    }
}
