package com.huanghuang.rsintegration.module.goety;

import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class RSClientAvailabilityCache {

    private static final Map<ResourceLocation, boolean[]> CACHE = new ConcurrentHashMap<>();

    private RSClientAvailabilityCache() {}

    public static void put(ResourceLocation recipeId, boolean[] results) {
        CACHE.put(recipeId, results);
    }

    @Nullable
    public static boolean[] get(ResourceLocation recipeId) {
        return CACHE.get(recipeId);
    }

    public static void clear() {
        CACHE.clear();
    }
}
