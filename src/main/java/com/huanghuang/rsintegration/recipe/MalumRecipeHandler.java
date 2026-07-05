package com.huanghuang.rsintegration.recipe;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.util.Reflect;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;

import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class MalumRecipeHandler implements ModRecipeHandler {

    private static final String IWC_CLASS = "team.lodestar.lodestone.systems.recipe.IngredientWithCount";

    @Override
    public ModType modType() { return ModType.byId("malum"); }

    @Override
    public boolean canHandle(Recipe<?> recipe) {
        return recipe.getClass().getName().startsWith("com.sammy.malum.");
    }

    @Override
    public ItemStack getResultItem(Recipe<?> recipe, RegistryAccess access) {
        for (String name : new String[]{"getResultItem", "getResult", "getOutput"}) {
            try {
                java.lang.reflect.Method m = recipe.getClass().getMethod(name);
                if (!ItemStack.class.isAssignableFrom(m.getReturnType())) continue;
                Object r = m.getParameterCount() == 1 ? m.invoke(recipe, access) : m.invoke(recipe);
                if (r instanceof ItemStack s && !s.isEmpty()) return s;
            } catch (Exception ignored) {}
        }
        // Fallback: direct field access (SpiritFocusingRecipe.output, etc.)
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
        Optional<Class<?>> iwcClass = Reflect.forName(IWC_CLASS);
        if (iwcClass.isEmpty()) return null;

        try {
            Field ingField = iwcClass.get().getDeclaredField("ingredient");
            ingField.setAccessible(true);
            Field countField = iwcClass.get().getDeclaredField("count");
            countField.setAccessible(true);
            List<IngredientSpec> result = new ArrayList<>();

            // Spirit/Focusing recipes: single "input" field (IngredientWithCount)
            Reflect.findField(recipe.getClass(), "input").ifPresent(inputField -> {
                if (iwcClass.get().isAssignableFrom(inputField.getType())) {
                    inputField.setAccessible(true);
                    try {
                        Object iwc = inputField.get(recipe);
                        if (iwc != null) {
                            Ingredient ing = (Ingredient) ingField.get(iwc);
                            int count = countField.getInt(iwc);
                            if (ing != null && count > 0) result.add(new IngredientSpec(ing, count));
                        }
                    } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI] Reflection probe failed", e); }
                }
            });

            // RunicWorkbenchRecipe: primaryInput + secondaryInput (IngredientWithCount)
            for (String fn : new String[]{"primaryInput", "secondaryInput"}) {
                readIwcField(recipe, fn, iwcClass.get(), ingField, countField, result);
            }

            // extraItems list (IngredientWithCount objects)
            readIwcList(recipe, "extraItems", iwcClass.get(), ingField, countField, result);

            // spirits list — SpiritWithCount implements IRecipeComponent, NOT IngredientWithCount.
            readSpiritList(recipe, "spirits", result);

            return result.isEmpty() ? null : result;
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private void readIwcList(Object recipe, String fieldName, Class<?> iwcClass,
                              Field ingField, Field countField, List<IngredientSpec> out) {
        Reflect.findField(recipe.getClass(), fieldName).ifPresent(f -> {
            if (!List.class.isAssignableFrom(f.getType())) return;
            f.setAccessible(true);
            try {
                List<?> list = (List<?>) f.get(recipe);
                if (list == null) return;
                for (Object iwc : list) {
                    if (iwcClass.isInstance(iwc)) {
                        Ingredient ing = (Ingredient) ingField.get(iwc);
                        int count = countField.getInt(iwc);
                        if (ing != null && count > 0) out.add(new IngredientSpec(ing, count));
                    }
                }
            } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI] Reflection probe failed", e); }
        });
    }

    private void readIwcField(Object recipe, String fieldName, Class<?> iwcClass,
                               Field ingField, Field countField, List<IngredientSpec> out) {
        Reflect.findField(recipe.getClass(), fieldName).ifPresent(f -> {
            if (!iwcClass.isAssignableFrom(f.getType())) return;
            f.setAccessible(true);
            try {
                Object iwc = f.get(recipe);
                if (iwc != null) {
                    Ingredient ing = (Ingredient) ingField.get(iwc);
                    int count = countField.getInt(iwc);
                    if (ing != null && count > 0) out.add(new IngredientSpec(ing, count));
                }
            } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI] Reflection probe failed", e); }
        });
    }

    @SuppressWarnings("unchecked")
    private void readSpiritList(Object recipe, String fieldName, List<IngredientSpec> out) {
        Reflect.findField(recipe.getClass(), fieldName).ifPresent(f -> {
            if (!List.class.isAssignableFrom(f.getType())) return;
            f.setAccessible(true);
            try {
                List<?> list = (List<?>) f.get(recipe);
                if (list == null) return;
                for (Object swc : list) {
                    // SpiritWithCount.getItem() returns the spirit shard Item
                    Optional<Object> itemOpt = Reflect.invoke(swc, "getItem");
                    if (itemOpt.isEmpty() || !(itemOpt.get() instanceof Item it)) continue;
                    int count = Reflect.getIntField(swc, "count").orElse(1);
                    if (count > 0) out.add(new IngredientSpec(Ingredient.of(it), count));
                }
            } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI] Reflection probe failed", e); }
        });
    }
}
