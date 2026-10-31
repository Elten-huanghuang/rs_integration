package com.huanghuang.rsintegration.client.storage;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class DiskTooltipBackgroundTest {

    @Test
    void specialItemsHaveBackgrounds() {
        for (String item : List.of("resonance_storage_disk", "resonance_network_generator",
                "unified_storage_disk", "resonant_experience_wand", "order_terminal",
                "resonant_mana_pool", "resonant_source_tank", "rs_pickup_upgrade",
                "rs_magnet_upgrade", "rs_refill_upgrade", "rs_feeding_upgrade", "rs_void_upgrade")) {
            assertNotNull(DiskTooltipBackground.themeFor(new ResourceLocation("rs_integration", item)), item);
        }
    }

    @Test
    void unrelatedItemsKeepTheVanillaTooltipBackground() {
        assertNull(DiskTooltipBackground.themeFor(
                new ResourceLocation("minecraft", "diamond")));
        assertNull(DiskTooltipBackground.themeFor(
                new ResourceLocation("rs_integration", "fluid_refund")));
        assertNull(DiskTooltipBackground.themeFor(
                new ResourceLocation("other_mod", "rs_void_upgrade")));
    }
}
