package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Immutable, reloadable soft preferences for broad ingredient variants. */
public final class MaterialVariantPreferences {
    private static volatile Snapshot current = compile(
            RSIntegrationConfig.DEFAULT_PREFERRED_INGREDIENT_VARIANTS);

    private MaterialVariantPreferences() {}

    public static void refresh() {
        List<? extends String> configured;
        try {
            configured = RSIntegrationConfig.PREFERRED_INGREDIENT_VARIANTS.get();
        } catch (RuntimeException | LinkageError ignored) {
            configured = RSIntegrationConfig.DEFAULT_PREFERRED_INGREDIENT_VARIANTS;
        }
        current = compile(configured);
    }

    public static Snapshot snapshot() {
        return current;
    }

    private static Snapshot compile(List<? extends String> configured) {
        Map<ResourceLocation, Integer> ranks = new LinkedHashMap<>();
        if (configured != null) {
            for (String value : configured) {
                ResourceLocation id = ResourceLocation.tryParse(value);
                if (id != null) ranks.putIfAbsent(id, ranks.size());
            }
        }
        return new Snapshot(Map.copyOf(ranks));
    }

    public record Snapshot(Map<ResourceLocation, Integer> ranks) {
        public Snapshot {
            ranks = Map.copyOf(ranks);
        }

        public int rank(ResourceLocation itemId) {
            return ranks.getOrDefault(itemId, Integer.MAX_VALUE);
        }

        public int rank(Item item) {
            ResourceLocation id = ForgeRegistries.ITEMS.getKey(item);
            return id == null ? Integer.MAX_VALUE : rank(id);
        }

        public boolean prefers(Item item) {
            return rank(item) != Integer.MAX_VALUE;
        }
    }
}
