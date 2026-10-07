package com.huanghuang.rsintegration.mods.cthulhucreatures;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.recipe.AbstractRecipeHandler;
import com.huanghuang.rsintegration.util.ModIds;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;

import javax.annotation.Nullable;
import java.lang.reflect.Method;
import java.util.List;

public final class FleshAltarRecipeHandler extends AbstractRecipeHandler {
    public static final String RECIPE_CLASS = CthulhuCreaturesRSModule.PACKAGE + "FleshAltarRecipe";

    static {
        registerRecipePrefixes(FleshAltarRecipeHandler.class, RECIPE_CLASS);
    }

    @Override public ModType modType() {
        return ModType.byId(ModIds.ID_CTHULHU_FLESH_ALTAR);
    }

    @Override public boolean canHandle(Recipe<?> recipe) {
        return RECIPE_CLASS.equals(recipe.getClass().getName());
    }

    @Override public boolean preferHandlerIngredients() {
        return true;
    }

    @Override public ItemStack getResultItem(Recipe<?> recipe, RegistryAccess access) {
        return recipe.getResultItem(access).copy();
    }

    @Nullable
    @Override public List<IngredientSpec> getIngredients(Recipe<?> recipe) {
        List<Ingredient> ingredients = recipe.getIngredients();
        if (ingredients.size() != 2 || ingredients.stream().anyMatch(Ingredient::isEmpty)) return null;
        try {
            // 原模组的 getIngredients 不包含数量；固定正向槽位顺序读取实际消耗量。
            Method consumed = recipe.getClass().getMethod("consumed", int.class, int.class);
            int first = ((Number) consumed.invoke(recipe, 0, 0)).intValue();
            int second = ((Number) consumed.invoke(recipe, 1, 0)).intValue();
            if (first < 1 || first > 64 || second < 1 || second > 64) return null;
            return List.of(new IngredientSpec(ingredients.get(0), first),
                    new IngredientSpec(ingredients.get(1), second));
        } catch (ReflectiveOperationException | ClassCastException exception) {
            RSIntegrationMod.LOGGER.warn("[RSI-FleshAltar] 无法读取祭坛配方数量 {}", recipe.getId(), exception);
            return null;
        }
    }

    static int craftingTime(Recipe<?> recipe) {
        try {
            return Math.max(1, ((Number) recipe.getClass().getMethod("getCraftingTime")
                    .invoke(recipe)).intValue());
        } catch (ReflectiveOperationException | ClassCastException exception) {
            return 60;
        }
    }
}
