package com.huanghuang.rsintegration.recipe.viewer;

import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;

/** Normalizes JEI pagination wrappers without destroying real JEI-only IDs. */
public final class RecipeIdNormalizer {

    private static final String MINECRAFT = "minecraft";
    private static final String ENCHANTMENT_INFO_PREFIX = "enchantment.";
    private static final String COSMOPOLITAN = "cosmopolitan";
    private static final String TISANE_PREFIX = "jei.tisane.";

    private RecipeIdNormalizer() {}

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

    /**
     * Apotheosis 的附魔信息页会使用 {@code minecraft:enchantment.<物品ID>}
     * 作为 JEI 展示键。它不是服务端配方，不能交给合成规划器处理。
     */
    public static boolean isEnchantmentInfoDisplayId(@Nullable ResourceLocation id) {
        return id != null
                && MINECRAFT.equals(id.getNamespace())
                && id.getPath().startsWith(ENCHANTMENT_INFO_PREFIX);
    }
}
