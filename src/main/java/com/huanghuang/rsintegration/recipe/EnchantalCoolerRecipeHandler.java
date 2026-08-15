package com.huanghuang.rsintegration.recipe;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.NonNullList;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;

import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

public final class EnchantalCoolerRecipeHandler extends AbstractRecipeHandler {

    private static final String RECIPE_CLASS =
            "com.renyigesai.immortalers_delight.recipe.EnchantalCoolerRecipe";
    private static final String SPECIAL_RECIPE_CLASS =
            "com.renyigesai.immortalers_delight.recipe.PillagerKnifeAddPotionRecipe";

    static {
        registerRecipePrefixes(EnchantalCoolerRecipeHandler.class,
                RECIPE_CLASS, SPECIAL_RECIPE_CLASS);
    }

    private static volatile Field inputItemsField;
    private static volatile boolean fieldProbed;

    @Override
    public ModType modType() { return ModType.byId("immortalers_delight"); }

    @Override
    public ItemStack getResultItem(Recipe<?> recipe, RegistryAccess access) {
        return ModRecipeHandlers.tryGetResultItem(recipe, access);
    }

    @Nullable
    @Override
    public List<IngredientSpec> getIngredients(Recipe<?> recipe) {
        List<IngredientSpec> specs = getInputSpecs(recipe);
        if (specs == null) return null;
        return appendContainerSpec(specs, getContainerItem(recipe));
    }

    @Nullable
    public static List<IngredientSpec> getInputSpecs(Recipe<?> recipe) {
        NonNullList<Ingredient> items = getInputItems(recipe);
        if (items == null || items.isEmpty()) return null;
        List<IngredientSpec> specs = new ArrayList<>(items.size());
        for (Ingredient ing : items) {
            if (!ing.isEmpty()) specs.add(new IngredientSpec(ing, 1));
        }
        return specs.isEmpty() ? null : specs;
    }

    static List<IngredientSpec> appendContainerSpec(List<IngredientSpec> inputSpecs,
                                                     ItemStack container) {
        List<IngredientSpec> specs = new ArrayList<>(inputSpecs.size() + 1);
        specs.addAll(inputSpecs);
        if (container != null && !container.isEmpty()) {
            specs.add(new IngredientSpec(Ingredient.of(container.copyWithCount(1)), 1));
        }
        return List.copyOf(specs);
    }

    public static ItemStack getContainerItem(Recipe<?> recipe) {
        if (recipe == null) return ItemStack.EMPTY;
        try {
            java.lang.reflect.Method method = recipe.getClass().getMethod("getContainer");
            Object value = method.invoke(recipe);
            if (value instanceof ItemStack stack && !stack.isEmpty()) return stack.copy();
        } catch (ReflectiveOperationException e) {
            RSIntegrationMod.LOGGER.debug("[RSI-Recipe] Enchantal Cooler getContainer failed", e);
        }
        for (Class<?> type = recipe.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField("container");
                field.setAccessible(true);
                Object value = field.get(recipe);
                if (value instanceof ItemStack stack && !stack.isEmpty()) return stack.copy();
                return ItemStack.EMPTY;
            } catch (NoSuchFieldException ignored) {
                // Continue through compatibility subclasses.
            } catch (ReflectiveOperationException e) {
                RSIntegrationMod.LOGGER.debug(
                        "[RSI-Recipe] Enchantal Cooler container field failed", e);
                return ItemStack.EMPTY;
            }
        }
        return ItemStack.EMPTY;
    }

    @Override
    public boolean hasRuntimeDependentPrimaryNbt(Recipe<?> recipe) {
        return SPECIAL_RECIPE_CLASS.equals(recipe.getClass().getName());
    }

    public static boolean isSupportedRecipeClassName(String className) {
        return RECIPE_CLASS.equals(className) || SPECIAL_RECIPE_CLASS.equals(className);
    }

    @Nullable
    @SuppressWarnings("unchecked")
    public static NonNullList<Ingredient> getInputItems(Recipe<?> recipe) {
        probeField();
        if (inputItemsField == null) return null;
        try {
            return (NonNullList<Ingredient>) inputItemsField.get(recipe);
        } catch (Exception e) {
            return null;
        }
    }

    private static void probeField() {
        if (fieldProbed) return;
        fieldProbed = true;
        try {
            Class<?> c = Class.forName(RECIPE_CLASS);
            inputItemsField = c.getDeclaredField("inputItems");
            inputItemsField.setAccessible(true);
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI-Recipe] reflection probe failed", e);
        }
    }
}
