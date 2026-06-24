package com.huanghuang.rsintegration.recipe;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.util.Reflect;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;

import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

final class MalumRecipeHandler implements ModRecipeHandler {

    private static final String IWC_CLASS = "team.lodestar.lodestone.systems.recipe.IngredientWithCount";

    @Override
    public ModType modType() { return ModType.MALUM; }

    @Override
    public boolean canHandle(Recipe<?> recipe) {
        return recipe.getClass().getName().startsWith("com.sammy.malum.");
    }

    @Override
    public ItemStack getResultItem(Recipe<?> recipe, RegistryAccess access) {
        return ModRecipeHandlers.tryGetResultItem(recipe, access);
    }

    @Nullable
    @Override
    public List<IngredientSpec> getIngredients(Recipe<?> recipe) {
        Optional<Class<?>> iwcClass = Reflect.forName(IWC_CLASS);
        if (iwcClass.isEmpty()) return null;

        try {
            Field ingField = iwcClass.get().getField("ingredient");
            Field countField = iwcClass.get().getField("count");
            List<IngredientSpec> result = new ArrayList<>();

            // Single input field
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

            // extraItems list
            readIwcList(recipe, "extraItems", iwcClass.get(), ingField, countField, result);

            // spirits list
            readIwcList(recipe, "spirits", iwcClass.get(), ingField, countField, result);

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
}
