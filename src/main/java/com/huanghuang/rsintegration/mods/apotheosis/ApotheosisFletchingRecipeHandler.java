package com.huanghuang.rsintegration.mods.apotheosis;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.recipe.ModRecipeHandler;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/** Recipe adapter for Apotheosis' ordered 1x3 fletching recipes. */
public final class ApotheosisFletchingRecipeHandler implements ModRecipeHandler {
    private static final String RECIPE_CLASS =
            "dev.shadowsoffire.apotheosis.village.fletching.FletchingRecipe";

    @Override
    public @Nonnull ModType modType() {
        return ModType.byId("apotheosis_fletching");
    }

    @Override
    public boolean canHandle(@Nonnull Recipe<?> recipe) {
        return recipe.getClass().getName().equals(RECIPE_CLASS);
    }

    @Override
    public @Nonnull ItemStack getResultItem(@Nonnull Recipe<?> recipe, @Nonnull RegistryAccess access) {
        return recipe.getResultItem(access).copy();
    }

    @Override
    public @Nullable List<IngredientSpec> getIngredients(Recipe<?> recipe) {
        List<Ingredient> ingredients = recipe.getIngredients();
        if (ingredients.size() != 3) return null;
        List<IngredientSpec> result = new ArrayList<>(3);
        for (Ingredient ingredient : ingredients) {
            if (ingredient.isEmpty()) return null;
            result.add(new IngredientSpec(ingredient, 1));
        }
        return result;
    }
}
