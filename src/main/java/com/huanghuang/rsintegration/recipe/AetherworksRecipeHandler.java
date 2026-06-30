package com.huanghuang.rsintegration.recipe;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

public final class AetherworksRecipeHandler implements ModRecipeHandler {

    @Override
    public ModType modType() { return ModType.byId("aetherworks_anvil"); }

    @Override
    public boolean canHandle(Recipe<?> recipe) {
        return recipe.getClass().getName().startsWith("net.sirplop.aetherworks.");
    }

    @Override
    public ItemStack getResultItem(Recipe<?> recipe, RegistryAccess access) {
        // Try 1-arg getResultItem(RegistryAccess) first — Recipe's default
        // delegates to the deprecated 0-arg getResultItem(), which AetheriumAnvilRecipe
        // overrides to return the real output.
        for (String name : new String[]{"getResultItem", "getResult", "getOutput", "getOutputCopy", "getAssembledItem"}) {
            boolean isResultItem = "getResultItem".equals(name);
            // 1-param
            for (java.lang.reflect.Method m : recipe.getClass().getMethods()) {
                if (!m.getName().equals(name)) continue;
                if (!ItemStack.class.isAssignableFrom(m.getReturnType())) continue;
                if (m.getParameterCount() != 1) continue;
                try {
                    Object r = m.invoke(recipe, access);
                    if (r instanceof ItemStack s && !s.isEmpty()) return s;
                } catch (Exception ignored) {}
            }
            // Skip no-arg getResultItem() — safety guard like ModRecipeHandlers.
            // AetheriumAnvilRecipe doesn't abuse the 0-arg overload (unlike WR),
            // but the 1-arg got here via Recipe's default delegation, so if
            // that returned EMPTY the 0-arg would too.
            if (isResultItem) continue;
            // 0-param fallback for other method names
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
