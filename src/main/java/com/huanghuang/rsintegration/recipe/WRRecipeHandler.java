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

        for (String name : new String[]{"getResultItem", "getResult", "getOutput", "getOutputCopy", "getAssembledItem"}) {
            boolean isResultItem = "getResultItem".equals(name);
            // Try 1-param version first — some recipes (e.g. ArcaneIteratorRecipe)
            // have a no-arg getResultItem() that returns the machine block itself,
            // while getResultItem(RegistryAccess) returns the real recipe output.
            for (java.lang.reflect.Method m : recipe.getClass().getMethods()) {
                if (!m.getName().equals(name)) continue;
                if (!ItemStack.class.isAssignableFrom(m.getReturnType())) continue;
                if (m.getParameterCount() != 1) continue;
                try {
                    Object r = m.invoke(recipe, access);
                    if (r instanceof ItemStack s && !s.isEmpty()) return s;
                } catch (Exception ignored) {}
            }
            // Skip no-arg getResultItem() — the deprecated overload that mods
            // abuse to return machine block icons.
            if (isResultItem) continue;
            // Fall back to no-arg version (other method names only)
            for (java.lang.reflect.Method m : recipe.getClass().getMethods()) {
                if (!m.getName().equals(name)) continue;
                if (!ItemStack.class.isAssignableFrom(m.getReturnType())) continue;
                if (m.getParameterCount() != 0) continue;
                try {
                    Object r = m.invoke(recipe);
                    if (r instanceof ItemStack s && !s.isEmpty()) return s;
                } catch (Exception ignored) {}
            }
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
