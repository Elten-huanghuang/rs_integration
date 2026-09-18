package com.huanghuang.rsintegration.config;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PreferredIngredientVariantsConfigTest {
    @Test
    void defaultsAreOrderedValidAndExcludeStateSensitiveCategories() {
        var defaults = RSIntegrationConfig.DEFAULT_PREFERRED_INGREDIENT_VARIANTS;

        assertEquals("minecraft:white_wool", defaults.get(0));
        assertTrue(defaults.indexOf("minecraft:glass")
                < defaults.indexOf("minecraft:white_stained_glass"));
        assertTrue(defaults.indexOf("minecraft:oak_log")
                < defaults.indexOf("minecraft:stripped_oak_log"));
        assertTrue(defaults.contains("minecraft:iron_ingot"));
        assertTrue(defaults.contains("minecraft:charcoal"));
        assertFalse(defaults.contains("minecraft:enchanted_book"));
        assertFalse(defaults.contains("minecraft:shulker_box"));
        assertEquals(defaults.size(), new HashSet<>(defaults).size());
        assertTrue(defaults.stream().allMatch(id -> ResourceLocation.tryParse(id) != null));
    }
}
