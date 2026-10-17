package com.huanghuang.rsintegration.recipe;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.util.ModIds;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;

import javax.annotation.Nullable;
import java.util.List;

/** 随意乐事炸锅的精确配方处理器。 */
public final class DeepFryingRecipeHandler extends AbstractRecipeHandler {

    public static final String RECIPE_CLASS =
            "com.va11halla.casualness_delight.recipe.DeepFryingRecipe";

    static {
        // 只注册具体类名，避免抢走 casualness_delight 其它配方。
        registerRecipePrefixes(DeepFryingRecipeHandler.class, RECIPE_CLASS);
    }

    @Override
    public ModType modType() {
        return ModType.byId(ModIds.ID_CD_DEEP_FRYING_PAN);
    }

    @Override
    public boolean canHandle(Recipe<?> recipe) {
        return recipe != null && RECIPE_CLASS.equals(recipe.getClass().getName());
    }

    @Override
    public boolean preferHandlerIngredients() {
        return true;
    }

    @Override
    public ItemStack getResultItem(Recipe<?> recipe, RegistryAccess access) {
        return ModRecipeHandlers.tryGetResultItem(recipe, access);
    }

    @Nullable
    @Override
    public List<IngredientSpec> getIngredients(Recipe<?> recipe) {
        if (!canHandle(recipe) || recipe.getIngredients().size() != 1) return null;
        var ingredient = recipe.getIngredients().get(0);
        return ingredient.isEmpty() ? null : List.of(new IngredientSpec(ingredient, 1));
    }
}
