package com.huanghuang.rsintegration.mods.farmersdelight;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.ScaledIngredientSpec;
import com.huanghuang.rsintegration.recipe.AbstractRecipeHandler;
import com.huanghuang.rsintegration.recipe.RecipeOutputSpec;
import com.huanghuang.rsintegration.util.ModIds;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;

import javax.annotation.Nullable;
import java.util.List;

/** 切割板专属配方语义，确保预览和执行都使用切割板机器类型。 */
public final class CuttingBoardRecipeHandler extends AbstractRecipeHandler {
    private final FarmersDelightRecipeHandler semantics = new FarmersDelightRecipeHandler();

    @Override
    public ModType modType() {
        return ModType.byId(ModIds.ID_FD_CUTTING_BOARD);
    }

    @Override
    public boolean canHandle(Recipe<?> recipe) {
        return FarmersDelightRecipeHandler.isCuttingBoardRecipe(recipe);
    }

    @Override
    public boolean preferHandlerIngredients() {
        return true;
    }

    @Override
    public ItemStack getResultItem(Recipe<?> recipe, RegistryAccess access) {
        return semantics.getResultItem(recipe, access);
    }

    @Nullable
    @Override
    public List<IngredientSpec> getIngredients(Recipe<?> recipe) {
        return semantics.getIngredients(recipe);
    }

    @Override
    public List<ItemStack> getSecondaryOutputs(Recipe<?> recipe, RegistryAccess access) {
        return semantics.getSecondaryOutputs(recipe, access);
    }

    @Override
    public List<RecipeOutputSpec> getDeclaredOutputs(Recipe<?> recipe, RegistryAccess access) {
        return FarmersDelightRecipeHandler.cuttingBoardDeclaredOutputs(recipe);
    }

    @Override
    public boolean hasStructuredOutputContract(Recipe<?> recipe) {
        return true;
    }

    @Override
    public List<ScaledIngredientSpec> getRecursiveScaledIngredients(
            Recipe<?> recipe, @Nullable List<ScaledIngredientSpec> ingredients) {
        return semantics.getRecursiveScaledIngredients(recipe, ingredients);
    }
}
