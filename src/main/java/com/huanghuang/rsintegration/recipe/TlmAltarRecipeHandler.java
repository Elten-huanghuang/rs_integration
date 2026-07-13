package com.huanghuang.rsintegration.recipe;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

public final class TlmAltarRecipeHandler extends AbstractRecipeHandler {

    static {
        registerRecipePrefixes(TlmAltarRecipeHandler.class,
                "com.github.tartaricacid.touhoulittlemaid.");
    }

    @Override
    public ModType modType() { return ModType.byId("touhou_little_maid"); }

    @Override
    public ItemStack getResultItem(Recipe<?> recipe, RegistryAccess access) {
        // Probe getResult/getOutput/getOutputCopy/getAssembledItem first,
        // fall back to getResultItem last — some recipes only expose
        // output through getResultItem.
        for (String name : new String[]{"getResult", "getOutput", "getOutputCopy", "getAssembledItem", "getResultItem"}) {
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
        // AltarRecipe stores the output in a private resultItem field.
        // Entity-summoning recipes have an empty resultItem (output is
        // an entity), but item-crafting recipes have a real ItemStack
        // that getResultItem(RegistryAccess) returns.
        ItemStack field = tryGetOutputField(recipe);
        if (!field.isEmpty()) return field;
        return ItemStack.EMPTY;
    }

    /** Scan fields for an ItemStack that looks like a recipe output. */
    private static ItemStack tryGetOutputField(Recipe<?> recipe) {
        Class<?> scan = recipe.getClass();
        while (scan != null && scan != Object.class) {
            for (java.lang.reflect.Field f : scan.getDeclaredFields()) {
                if (!ItemStack.class.isAssignableFrom(f.getType())) continue;
                // Only accept fields whose name looks like an output — otherwise an
                // unrelated ItemStack field declared first (icon, cached input,
                // container) would be mis-read as the recipe product.
                String fn = f.getName().toLowerCase(java.util.Locale.ROOT);
                if (!fn.contains("output") && !fn.contains("result") && !fn.contains("assembled")) continue;
                f.setAccessible(true);
                try {
                    ItemStack s = (ItemStack) f.get(recipe);
                    if (s != null && !s.isEmpty()) return s.copy();
                } catch (Exception e) {
                    RSIntegrationMod.LOGGER.debug("[RSI-Recipe] reflection probe failed", e);
                }
            }
            scan = scan.getSuperclass();
        }
        return ItemStack.EMPTY;
    }

    @Nullable
    @Override
    public List<IngredientSpec> getIngredients(Recipe<?> recipe) {
        List<Ingredient> ingredients = recipe.getIngredients();
        if (ingredients.isEmpty()) return null;
        List<IngredientSpec> specs = new ArrayList<>();
        for (Ingredient ing : ingredients) {
            if (!ing.isEmpty()) specs.add(new IngredientSpec(ing, 1));
        }
        return specs.isEmpty() ? null : specs;
    }
}
