package com.huanghuang.rsintegration.mods.tetra.client;

import net.minecraft.network.chat.Component;

public enum TetraMaterialSortMode {
    DEFAULT("gui.rs_integration.tetra_sort.mode.default"),
    CATEGORY("gui.rs_integration.tetra_sort.mode.category"),
    HARDNESS("tetra.holo.craft.materials.stat.primary"),
    DENSITY("tetra.holo.craft.materials.stat.secondary"),
    FLEXIBILITY("tetra.holo.craft.materials.stat.tertiary"),
    DURABILITY("tetra.holo.craft.materials.stat.durability"),
    TOOL_LEVEL("tetra.holo.craft.materials.stat.tool_level"),
    TOOL_EFFICIENCY("tetra.holo.craft.materials.stat.tool_efficiency"),
    INTEGRITY_GAIN("tetra.holo.craft.materials.stat.integrity"),
    INTEGRITY_COST("tetra.holo.craft.materials.stat.integrity"),
    MAGIC_CAPACITY("tetra.holo.craft.materials.stat.magic_capacity");

    private final String translationKey;

    TetraMaterialSortMode(String key) {
        translationKey = key;
    }

    public Component label() {
        return Component.translatable(translationKey);
    }

    public static TetraMaterialSortMode next(TetraMaterialSortMode current) {
        TetraMaterialSortMode[] modes = values();
        return modes[(current.ordinal() + 1) % modes.length];
    }
}
