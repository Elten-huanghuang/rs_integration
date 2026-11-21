package com.huanghuang.rsintegration.recipe.viewer;

import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.util.regex.Pattern;

/** Normalizes JEI pagination wrappers without destroying real JEI-only IDs. */
public final class RecipeIdNormalizer {

    private static final String MINECRAFT = "minecraft";
    private static final String ENCHANTMENT_INFO_PREFIX = "enchantment.";
    private static final String COSMOPOLITAN = "cosmopolitan";
    private static final String TISANE_PREFIX = "jei.tisane.";
    private static final Pattern RESOURCE_NAMESPACE = Pattern.compile("[a-z0-9._-]+");
    private static final Pattern RESOURCE_PATH = Pattern.compile("[a-z0-9/._-]+");

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
     * JEI 铁砧的附魔展示 ID 只用于客户端。其他模组也可能放宽客户端的
     * ResourceLocation 校验，发包前按网络两端通用的格式重新校验。
     */
    public static boolean canRequestJeiRecipe(@Nullable ResourceLocation id) {
        return id != null
                && !(MINECRAFT.equals(id.getNamespace()) && id.getPath().startsWith(ENCHANTMENT_INFO_PREFIX))
                && RESOURCE_NAMESPACE.matcher(id.getNamespace()).matches()
                && RESOURCE_PATH.matcher(id.getPath()).matches();
    }
}
