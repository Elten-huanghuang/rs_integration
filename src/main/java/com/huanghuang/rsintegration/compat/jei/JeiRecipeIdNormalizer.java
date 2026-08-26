package com.huanghuang.rsintegration.compat.jei;

import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;

/** Normalizes JEI pagination wrappers without destroying real JEI-only IDs. */
public final class JeiRecipeIdNormalizer {

    private static final String COSMOPOLITAN = "cosmopolitan";
    private static final String TISANE_PREFIX = "jei.tisane.";

    private JeiRecipeIdNormalizer() {}

    @Nullable
    public static ResourceLocation normalize(@Nullable ResourceLocation id) {
        if (id == null) return null;
        String path = id.getPath();
        if (!path.startsWith("jei.") || isCosmopolitanTisane(id)) return id;

        String inner = path.substring(4);
        int slash = inner.lastIndexOf('/');
        if (slash > 0 && slash < inner.length() - 1) {
            inner = inner.substring(0, slash);
        }
        return new ResourceLocation(id.getNamespace(), inner);
    }

    public static boolean isCosmopolitanTisane(@Nullable ResourceLocation id) {
        return id != null
                && COSMOPOLITAN.equals(id.getNamespace())
                && id.getPath().startsWith(TISANE_PREFIX)
                && id.getPath().length() > TISANE_PREFIX.length();
    }
}
