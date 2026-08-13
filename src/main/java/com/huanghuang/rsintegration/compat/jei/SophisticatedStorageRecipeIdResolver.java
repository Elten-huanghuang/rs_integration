package com.huanghuang.rsintegration.compat.jei;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.Recipe;

import javax.annotation.Nullable;

/** Resolves Sophisticated Storage's grouped JEI display IDs to real recipes. */
public final class SophisticatedStorageRecipeIdResolver {
    private static final String GROUPED_RECIPE_CLASS =
            "net.p3pp3rf1y.sophisticatedstorage.compat.recipeviewers.common.TierUpgradeDisplayRecipe";

    private SophisticatedStorageRecipeIdResolver() {}

    @Nullable
    public static ResourceLocation resolve(Object displayRecipe) {
        if (displayRecipe == null
                || !GROUPED_RECIPE_CLASS.equals(displayRecipe.getClass().getName())) {
            return null;
        }
        try {
            Object wrapped = displayRecipe.getClass().getMethod("recipe").invoke(displayRecipe);
            if (!(wrapped instanceof Recipe<?> recipe) || wrapped == displayRecipe) return null;
            return StandardRecipeIdResolver.resolve(recipe);
        } catch (ReflectiveOperationException | LinkageError ignored) {
            return null;
        }
    }
}
