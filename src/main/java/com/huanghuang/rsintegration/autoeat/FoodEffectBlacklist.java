package com.huanghuang.rsintegration.autoeat;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.food.FoodProperties;

import java.util.Set;

final class FoodEffectBlacklist {

    private FoodEffectBlacklist() {}

    static boolean matches(FoodProperties properties, Set<ResourceLocation> blacklist) {
        if (properties == null || blacklist.isEmpty()) return false;
        for (var entry : properties.getEffects()) {
            ResourceLocation key = BuiltInRegistries.MOB_EFFECT.getKey(entry.getFirst().getEffect());
            if (key != null && blacklist.contains(key)) return true;
        }
        return false;
    }
}
