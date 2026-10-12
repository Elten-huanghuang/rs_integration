package com.huanghuang.rsintegration.recipe;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.util.ModIds;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/** 怪物锅使用独立配方，食材和每份成品的盛装容器一起进入合成计划。 */
public final class DungeonsDelightRecipeHandler extends AbstractRecipeHandler {
    public static final String RECIPE_CLASS =
            "net.yirmiri.dungeonsdelight.common.block.entity.container.MonsterPotRecipe";

    @Override
    public ModType modType() {
        return ModType.byId(ModIds.ID_DD_MONSTER_POT);
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
        if (!canHandle(recipe)) return null;
        List<IngredientSpec> inputs = new ArrayList<>();
        for (Ingredient ingredient : recipe.getIngredients()) {
            if (!ingredient.isEmpty()) inputs.add(new IngredientSpec(ingredient, 1));
        }
        return inputs.isEmpty() ? null : FarmersDelightRecipeHandler.appendOutputContainerSpec(
                inputs, FarmersDelightRecipeHandler.getOutputContainer(recipe));
    }
}
