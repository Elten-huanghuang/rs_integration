package com.huanghuang.rsintegration.mods.youkaishomecoming;

import com.huanghuang.rsintegration.util.ModIds;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

/** Shared registry-id rules for Youkai's Homecoming and Gensokyo Delight. */
public final class YoukaiRegistryIds {
    public static final List<String> MOD_IDS = List.of(
            ModIds.YOUKAISHOMECOMING,
            ModIds.YOUKAISFEASTS
    );

    private YoukaiRegistryIds() {}

    public static boolean isSupportedNamespace(String namespace) {
        return MOD_IDS.contains(namespace);
    }

    public static boolean matches(ResourceLocation id, String path) {
        return id != null && isSupportedNamespace(id.getNamespace()) && path.equals(id.getPath());
    }

    public static String stringId(String namespace, String path) {
        return namespace + ":" + path;
    }

    public static ResourceLocation id(String namespace, String path) {
        return new ResourceLocation(namespace, path);
    }
}
