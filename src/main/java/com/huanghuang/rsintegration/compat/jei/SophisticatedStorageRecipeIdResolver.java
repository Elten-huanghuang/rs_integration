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
        if (displayRecipe == null) {
            return null;
        }
        try {
            String className = displayRecipe.getClass().getName();
            if (GROUPED_RECIPE_CLASS.equals(className)) {
                Object wrapped = displayRecipe.getClass().getMethod("recipe").invoke(displayRecipe);
                if (!(wrapped instanceof Recipe<?> recipe) || wrapped == displayRecipe) return null;
                return StandardRecipeIdResolver.resolve(recipe);
            }

            // Sophisticated Core 1.3+ exposes grouped storage displays as
            // generated CraftingRecipe subclasses. Their spec carries the
            // real server recipe IDs that the display replaces.
            if (className.startsWith(
                    "net.p3pp3rf1y.sophisticatedcore.compat.recipeviewers.common.CraftingDisplaySpec$")) {
                Object spec = displayRecipe.getClass().getMethod("spec").invoke(displayRecipe);
                Object ids = spec.getClass().getMethod("replacedRecipeIds").invoke(spec);
                if (ids instanceof Iterable<?> iterable) {
                    for (Object id : iterable) {
                        if (id instanceof ResourceLocation location) return location;
                    }
                }
            }
            return null;
        } catch (ReflectiveOperationException | LinkageError ignored) {
            return null;
        }
    }
}
