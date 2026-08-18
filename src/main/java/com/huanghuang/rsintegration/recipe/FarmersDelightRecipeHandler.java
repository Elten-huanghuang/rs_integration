package com.huanghuang.rsintegration.recipe;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CampfireCookingRecipe;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.Ingredient;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

public final class FarmersDelightRecipeHandler extends AbstractRecipeHandler {

    private static final String COOKING_POT_CLASS =
            "vectorwing.farmersdelight.common.crafting.CookingPotRecipe";

    @Override
    public ModType modType() { return ModType.byId("farmersdelight"); }

    @Override
    public boolean canHandle(Recipe<?> recipe) {
        return recipe.getClass().getName().equals(COOKING_POT_CLASS)
                || recipe instanceof CampfireCookingRecipe;
    }

    @Override
    public ItemStack getResultItem(Recipe<?> recipe, RegistryAccess access) {
        if (recipe instanceof CampfireCookingRecipe) {
            return CampfireRecipeSupport.resolveOutput(recipe, access);
        }
        return ModRecipeHandlers.tryGetResultItem(recipe, access);
    }

    @Nullable
    @Override
    public List<IngredientSpec> getIngredients(Recipe<?> recipe) {
        if (recipe instanceof CampfireCookingRecipe) {
            Ingredient ing = recipe.getIngredients().get(0);
            if (ing.isEmpty()) return null;
            return List.of(new IngredientSpec(ing, 1));
        }
        if (recipe.getClass().getName().equals(COOKING_POT_CLASS)) {
            List<Ingredient> ingredients = recipe.getIngredients();
            if (ingredients.isEmpty()) return null;
            List<IngredientSpec> specs = new ArrayList<>();
            for (Ingredient ing : ingredients) {
                if (!ing.isEmpty()) specs.add(new IngredientSpec(ing, 1));
            }
            return specs.isEmpty()
                    ? null
                    : appendOutputContainerSpec(specs, getOutputContainer(recipe));
        }
        return null;
    }

    /** Get cook time for a CookingPotRecipe or CampfireCookingRecipe, in ticks. */
    public static int getCookTime(Recipe<?> recipe) {
        if (recipe instanceof CampfireCookingRecipe ccr) {
            return ccr.getCookingTime();
        }
        if (recipe.getClass().getName().equals(COOKING_POT_CLASS)) {
            try {
                return (int) recipe.getClass().getMethod("getCookTime").invoke(recipe);
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.debug("[RSI-Recipe] reflection probe failed", e);
            }
        }
        return 200; // fallback
    }

    /** Get the output container item for a CookingPotRecipe (e.g., bowl). */
    public static ItemStack getOutputContainer(Recipe<?> recipe) {
        if (recipe.getClass().getName().equals(COOKING_POT_CLASS)) {
            try {
                ItemStack container = (ItemStack) recipe.getClass()
                        .getMethod("getOutputContainer").invoke(recipe);
                return container == null ? ItemStack.EMPTY : container.copy();
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.debug("[RSI-Recipe] reflection probe failed", e);
            }
        }
        return ItemStack.EMPTY;
    }

    static List<IngredientSpec> appendOutputContainerSpec(
            List<IngredientSpec> inputSpecs, ItemStack container) {
        List<IngredientSpec> specs = new ArrayList<>(inputSpecs.size() + 1);
        specs.addAll(inputSpecs);
        if (container != null && !container.isEmpty()) {
            specs.add(new IngredientSpec(Ingredient.of(container.copyWithCount(1)), 1));
        }
        return List.copyOf(specs);
    }
}
