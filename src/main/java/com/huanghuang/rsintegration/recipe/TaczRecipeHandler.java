package com.huanghuang.rsintegration.recipe;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;

import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

public final class TaczRecipeHandler implements ModRecipeHandler {

    private static final String RECIPE_CLASS = "com.tacz.guns.crafting.GunSmithTableRecipe";

    @Override
    public ModType modType() { return ModType.byId("tacz"); }

    @Override
    public boolean canHandle(Recipe<?> recipe) {
        return recipe.getClass().getName().equals(RECIPE_CLASS);
    }

    @Override
    public ItemStack getResultItem(Recipe<?> recipe, RegistryAccess registryAccess) {
        Class<?> clazz = recipe.getClass();

        // 1. Standard 1.20+ RegistryAccess overload
        try {
            ItemStack result = recipe.getResultItem(registryAccess);
            if (result != null && !result.isEmpty()) return result.copy();
        } catch (Exception ignored) {}

        // 2. No-arg getResultItem() — deprecated but many mod authors override
        //    only this one.  The global ModRecipeHandlers probe skips it to
        //    avoid WR/Malum machine block icons, but this handler is TACZ-only
        //    so it's safe to call.
        try {
            Method m = clazz.getMethod("getResultItem");
            ItemStack result = (ItemStack) m.invoke(recipe);
            if (result != null && !result.isEmpty()) return result.copy();
        } catch (Exception ignored) {}

        // 3. Other common result method names
        for (String methodName : new String[]{"getResult", "getOutput", "getRecipeOutput"}) {
            try {
                Method m = clazz.getMethod(methodName);
                ItemStack result = (ItemStack) m.invoke(recipe);
                if (result != null && !result.isEmpty()) return result.copy();
            } catch (Exception ignored) {}
        }

        // 4. Smart field scan: walk the class hierarchy, skip input fields,
        //    prioritise fields with NBT or output-suggesting names.
        Class<?> scan = clazz;
        while (scan != null && scan != Object.class) {
            for (Field f : scan.getDeclaredFields()) {
                if (f.getType() != ItemStack.class) continue;

                String name = f.getName().toLowerCase(java.util.Locale.ROOT);
                // Absolutely skip input-side fields — TACZ names them
                // input, ingredient, attachment_in, etc.
                if (name.contains("input") || name.contains("ingredient") || name.equals("in")) {
                    continue;
                }

                f.setAccessible(true);
                try {
                    ItemStack stack = (ItemStack) f.get(recipe);
                    if (stack != null && !stack.isEmpty()) {
                        // NBT-bearing stacks are almost certainly the real output.
                        // Also accept fields explicitly named output/result.
                        if (stack.hasTag() || name.contains("out") || name.contains("result")) {
                            return stack.copy();
                        }
                    }
                } catch (Exception ignored) {}
            }
            scan = scan.getSuperclass();
        }

        return ItemStack.EMPTY;
    }

    @Nullable
    @Override
    public List<IngredientSpec> getIngredients(Recipe<?> recipe) {
        try {
            Method getInputs = recipe.getClass().getMethod("getInputs");
            List<?> inputs = (List<?>) getInputs.invoke(recipe);
            List<IngredientSpec> specs = new ArrayList<>();
            for (Object input : inputs) {
                Method getIngredient = input.getClass().getMethod("getIngredient");
                Method getCount = input.getClass().getMethod("getCount");
                Ingredient ing = (Ingredient) getIngredient.invoke(input);
                int count = (int) getCount.invoke(input);
                if (!ing.isEmpty()) specs.add(new IngredientSpec(ing, count));
            }
            return specs.isEmpty() ? null : specs;
        } catch (Exception e) {
            return null;
        }
    }
}
