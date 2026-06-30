package com.huanghuang.rsintegration.recipe;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.util.Reflect;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

public final class WRRecipeHandler implements ModRecipeHandler {

    @Override
    public ModType modType() { return ModType.byId("wizards_reborn"); }

    @Override
    public boolean canHandle(Recipe<?> recipe) {
        return recipe.getClass().getName().startsWith("mod.maxbogomol.wizards_reborn.");
    }

    @Override
    public ItemStack getResultItem(Recipe<?> recipe, RegistryAccess access) {
        // CrystalRitualRecipe.getResultItem(RegistryAccess) returns EMPTY and
        // getResultItem() returns RUNIC_PEDESTAL (the machine block icon).
        // Crystal rituals produce runtime-determined effects (breeding,
        // fertility, infusion), not statically-declared items.  Returning
        // EMPTY skips them in RecipeIndex (no corruption) and avoids the
        // plan builder showing the machine block as the output.
        String className = recipe.getClass().getName();
        if (className.endsWith("CrystalRitualRecipe")) return ItemStack.EMPTY;

        // Probe getResult/getOutput/getOutputCopy/getAssembledItem first —
        // these are the canonical output methods that don't delegate to
        // the deprecated 0-arg getResultItem() which WR abuses to return
        // machine block icons.  Fall back to getResultItem last because
        // some recipes (e.g. ArcaneIteratorRecipe) only expose output
        // through it.
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

        // Field-scanning fallback: some WR recipes (e.g. ArcaneIteratorRecipe)
        // may not expose output through the standard methods at runtime if
        // Forge SRG→MCP remapping hasn't been applied yet or the recipe uses
        // a different output mechanism.  Scan for common output field names.
        return scanOutputField(recipe);
    }

    private static ItemStack scanOutputField(Object recipe) {
        Class<?> scan = recipe.getClass();
        while (scan != null && scan != Object.class) {
            for (java.lang.reflect.Field f : scan.getDeclaredFields()) {
                if (!ItemStack.class.isAssignableFrom(f.getType())) continue;
                String fn = f.getName();
                if (fn.equals("output") || fn.equals("result") || fn.equals("resultItem")) {
                    f.setAccessible(true);
                    try {
                        ItemStack s = (ItemStack) f.get(recipe);
                        if (s != null && !s.isEmpty()) return s.copy();
                    } catch (Exception ignored) {}
                }
            }
            scan = scan.getSuperclass();
        }
        return ItemStack.EMPTY;
    }

    @Nullable
    @Override
    @SuppressWarnings("unchecked")
    public List<IngredientSpec> getIngredients(Recipe<?> recipe) {
        // WR uses the standard extractIngredients probe chain
        List<Ingredient> ingredients = CraftPacketUtils.extractIngredients(recipe);
        if (ingredients == null || ingredients.isEmpty()) return null;
        List<IngredientSpec> specs = new ArrayList<>();
        for (Ingredient ing : ingredients) {
            if (!ing.isEmpty()) specs.add(new IngredientSpec(ing, 1));
        }
        return specs.isEmpty() ? null : specs;
    }
}
