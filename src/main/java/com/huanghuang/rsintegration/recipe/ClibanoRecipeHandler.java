package com.huanghuang.rsintegration.recipe;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.util.ModIds;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;

import javax.annotation.Nullable;
import java.util.List;

/** Recipe metadata for Forbidden & Arcanus Clibano combustion. */
public final class ClibanoRecipeHandler extends AbstractRecipeHandler {

    private static final String RECIPE_CLASS =
            "com.stal111.forbidden_arcanus.common.recipe.ClibanoRecipe";

    @Override
    public ModType modType() {
        return ModType.byId(ModIds.ID_FA_CLIBANO);
    }

    @Override
    public boolean canHandle(Recipe<?> recipe) {
        return recipe != null && RECIPE_CLASS.equals(recipe.getClass().getName());
    }

    @Override
    public ItemStack getResultItem(Recipe<?> recipe, RegistryAccess access) {
        ItemStack result = recipe.getResultItem(access);
        return result == null ? ItemStack.EMPTY : result.copy();
    }

    @Nullable
    @Override
    public List<IngredientSpec> getIngredients(Recipe<?> recipe) {
        List<Ingredient> ingredients = recipe.getIngredients();
        if (ingredients.isEmpty() || ingredients.get(0).isEmpty()) return null;
        return List.of(new IngredientSpec(ingredients.get(0), 1));
    }
}
