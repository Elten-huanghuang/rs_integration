package com.huanghuang.rsintegration.compat.jei;

import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;

/** Prevents a shared recipe class from borrowing an unrelated machine binding. */
public final class JeiMachineCategoryPolicy {

    private static final String FD_COOKING_RECIPE =
            "vectorwing.farmersdelight.common.crafting.CookingPotRecipe";
    private static final ResourceLocation FD_COOKING_CATEGORY =
            new ResourceLocation("farmersdelight", "cooking");

    private JeiMachineCategoryPolicy() {}

    public static boolean allowClassFallback(
            @Nullable ResourceLocation categoryId,
            String recipeClassName,
            @Nullable String explicitCategoryFilter) {
        if (explicitCategoryFilter != null) return true;
        if (!FD_COOKING_RECIPE.equals(recipeClassName)) return true;
        return categoryId == null || FD_COOKING_CATEGORY.equals(categoryId);
    }
}
