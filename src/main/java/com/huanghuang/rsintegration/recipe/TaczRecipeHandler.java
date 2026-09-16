package com.huanghuang.rsintegration.recipe;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.RSIntegrationMod;
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

public final class TaczRecipeHandler extends AbstractRecipeHandler {

    private static final String RECIPE_CLASS = "com.tacz.guns.crafting.GunSmithTableRecipe";

    static {
        registerRecipePrefixes(TaczRecipeHandler.class, RECIPE_CLASS);
    }

    @Override
    public ModType modType() { return ModType.byId("tacz"); }

    @Override
    public boolean supportsIntermediateProjection(Recipe<?> recipe) {
        // TACZ validates the bound workbench during execution; material inputs
        // and the initialized output are still safe to expose to the graph.
        return true;
    }

    @Override
    public ItemStack getResultItem(Recipe<?> recipe, RegistryAccess registryAccess) {
        Class<?> clazz = recipe.getClass();

        // GunSmithTableRecipe exposes the initialized result through getOutput;
        // this is more reliable for generated gun/ammo/attachment recipes than
        // the deprecated Recipe#getResultItem overload.
        try {
            Method m = clazz.getMethod("getOutput");
            Object value = m.invoke(recipe);
            if (value instanceof ItemStack result && !result.isEmpty()) return result.copy();
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI-Recipe] TACZ getOutput probe failed", e);
        }
        // Older/generated TACZ recipes expose the result wrapper instead of a
        // directly initialized ItemStack. Unwrap it before falling back to the
        // generic Recipe API.
        try {
            Method getResult = clazz.getMethod("getResult");
            Object wrapper = getResult.invoke(recipe);
            if (wrapper != null) {
                Method nested = wrapper.getClass().getMethod("getResult");
                Object value = nested.invoke(wrapper);
                if (value instanceof ItemStack result && !result.isEmpty()) return result.copy();
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI-Recipe] TACZ result-wrapper probe failed", e);
        }
        // Resource-generated recipes can reach the catalog before TACZ has run
        // its result-wrapper initialization hook.  Initialize once and retry
        // the canonical output accessors; init() is idempotent in TACZ.
        try {
            Method init = clazz.getMethod("init");
            init.invoke(recipe);
            Method getOutput = clazz.getMethod("getOutput");
            Object value = getOutput.invoke(recipe);
            if (value instanceof ItemStack result && !result.isEmpty()) return result.copy();
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI-Recipe] TACZ init/output probe failed", e);
        }

        // 1. Standard 1.20+ RegistryAccess overload
        try {
            ItemStack result = recipe.getResultItem(registryAccess);
            if (result != null && !result.isEmpty()) return result.copy();
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI-Recipe] reflection probe failed", e);
        }

        // 2. No-arg getResultItem() — deprecated but many mod authors override
        //    only this one.  The global ModRecipeHandlers probe skips it to
        //    avoid WR/Malum machine block icons, but this handler is TACZ-only
        //    so it's safe to call.
        try {
            Method m = clazz.getMethod("getResultItem");
            ItemStack result = (ItemStack) m.invoke(recipe);
            if (result != null && !result.isEmpty()) return result.copy();
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI-Recipe] reflection probe failed", e);
        }

        // 3. Other common result method names
        for (String methodName : new String[]{"getResult", "getOutput", "getRecipeOutput"}) {
            try {
                Method m = clazz.getMethod(methodName);
                ItemStack result = (ItemStack) m.invoke(recipe);
                if (result != null && !result.isEmpty()) return result.copy();
            } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI-Recipe] reflection probe failed", e);
        }
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
