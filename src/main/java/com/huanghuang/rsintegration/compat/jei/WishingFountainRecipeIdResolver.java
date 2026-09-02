package com.huanghuang.rsintegration.compat.jei;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;

/** Recovers native recipe IDs from Wishing Fountain's JEI-only wrapper. */
public final class WishingFountainRecipeIdResolver {
    public static final String WRAPPER_CLASS =
            "io.github.poisonsheep.wishingfountain.compat.jei.WFRecipeWrapper";
    private static final String PREFIX = "jei.";
    private static final String MIDDLE = ".altar_craft.";
    private static final String SUFFIX = ".result";

    private WishingFountainRecipeIdResolver() {}

    @Nullable
    public static ResourceLocation resolve(Object recipe) {
        if (recipe == null || !WRAPPER_CLASS.equals(recipe.getClass().getName())) return null;
        try {
            Object output = recipe.getClass().getMethod("getOutput").invoke(recipe);
            if (!(output instanceof ItemStack stack) || stack.isEmpty()) return null;
            Object value = recipe.getClass().getMethod("getLangKey").invoke(recipe);
            return value instanceof String key ? resolveLangKey(key) : null;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    public static boolean isWrapper(Object recipe) {
        return recipe != null && WRAPPER_CLASS.equals(recipe.getClass().getName());
    }

    @Nullable
    static ResourceLocation resolveLangKey(String key) {
        if (key == null || !key.startsWith(PREFIX) || !key.endsWith(SUFFIX)) return null;
        int middle = key.indexOf(MIDDLE, PREFIX.length());
        if (middle < 0) return null;
        String namespace = key.substring(PREFIX.length(), middle);
        String path = key.substring(middle + MIDDLE.length(), key.length() - SUFFIX.length());
        return ResourceLocation.tryBuild(namespace, path);
    }
}
