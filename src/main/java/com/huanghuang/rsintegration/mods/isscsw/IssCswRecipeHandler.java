package com.huanghuang.rsintegration.mods.isscsw;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.recipe.ModRecipeHandler;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraftforge.common.crafting.CompoundIngredient;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

final class IssCswRecipeHandler implements ModRecipeHandler {
    @Override public ModType modType() { return ModType.byId(IssCswRSModule.SPELL_FORGE_TYPE); }
    @Override public boolean canHandle(Recipe<?> recipe) {
        return recipe.getClass().getName().equals(IssCswRSModule.RECIPE_CLASS);
    }
    @Override public boolean preferHandlerIngredients() { return true; }

    @Override
    public ItemStack getResultItem(Recipe<?> recipe, RegistryAccess access) {
        try {
            Method method = recipe.getClass().getMethod("getResult");
            Object result = method.invoke(recipe);
            return result instanceof ItemStack stack ? stack.copy() : ItemStack.EMPTY;
        } catch (ReflectiveOperationException | LinkageError e) {
            RSIntegrationMod.LOGGER.warn("[RSI-ISS-CSW] Cannot read spell forge result from {}",
                    recipe.getClass().getName(), e);
            return ItemStack.EMPTY;
        }
    }

    @Override
    public List<IngredientSpec> getIngredients(Recipe<?> recipe) {
        try {
            Ingredient main = (Ingredient) field(recipe, "mainspell");
            Ingredient[] first = (Ingredient[]) field(recipe, "addition1");
            Ingredient[] second = (Ingredient[]) field(recipe, "addition2");
            if (main == null || first == null || first.length == 0 || second == null || second.length == 0) {
                return null;
            }
            return List.of(new IngredientSpec(main, 1),
                    new IngredientSpec(CompoundIngredient.of(first), 1),
                    new IngredientSpec(CompoundIngredient.of(second), 1));
        } catch (ReflectiveOperationException | ClassCastException | LinkageError e) {
            RSIntegrationMod.LOGGER.warn("[RSI-ISS-CSW] Cannot read spell forge ingredients from {}",
                    recipe.getClass().getName(), e);
            return null;
        }
    }

    private static Object field(Object target, String name) throws ReflectiveOperationException {
        Field field = target.getClass().getField(name);
        return field.get(target);
    }
}
