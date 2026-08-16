package com.huanghuang.rsintegration.mods.avaritia;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CraftingTableBatchDelegateTest {

    @Test
    void recognizesAvaritiaCraftingTableTiersByRegistryId() {
        assertEquals(1, CraftingTableBatchDelegate.machineTier(
                new ResourceLocation("avaritia", "sculk_crafting_table")));
        assertEquals(2, CraftingTableBatchDelegate.machineTier(
                new ResourceLocation("avaritia", "nether_crafting_table")));
        assertEquals(3, CraftingTableBatchDelegate.machineTier(
                new ResourceLocation("avaritia", "end_crafting_table")));
        assertEquals(4, CraftingTableBatchDelegate.machineTier(
                new ResourceLocation("avaritia", "extreme_crafting_table")));
    }

    @Test
    void ignoresNonAvaritiaAndUnknownBlocks() {
        assertEquals(0, CraftingTableBatchDelegate.machineTier(
                new ResourceLocation("minecraft", "crafting_table")));
        assertEquals(0, CraftingTableBatchDelegate.machineTier(
                new ResourceLocation("avaritia", "compressed_crafting_table")));
        assertEquals(0, CraftingTableBatchDelegate.machineTier(null));
    }
}
