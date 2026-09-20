package com.huanghuang.rsintegration.mods.vanilla;

import com.huanghuang.rsintegration.crafting.batch.BatchConcurrencyCapabilities;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CookingMachineFamilyTest {
    @Test
    void knownRecipeTypeIdsMapToFamilies() {
        assertEquals(CookingMachineFamily.FURNACE,
                CookingMachineFamily.fromRecipeTypeId(new ResourceLocation("minecraft", "smelting")));
        assertEquals(CookingMachineFamily.FURNACE,
                CookingMachineFamily.fromRecipeTypeId(new ResourceLocation("brickfurnace", "smelting")));
        assertEquals(CookingMachineFamily.BLAST_FURNACE,
                CookingMachineFamily.fromRecipeTypeId(new ResourceLocation("brickfurnace", "blasting")));
        assertEquals(CookingMachineFamily.SMOKER,
                CookingMachineFamily.fromRecipeTypeId(new ResourceLocation("brickfurnace", "smoking")));
        assertEquals(CookingMachineFamily.UNKNOWN,
                CookingMachineFamily.fromRecipeTypeId(new ResourceLocation("other", "smelting")));
    }

    @Test
    void campfireOnlyRecognizesMinecraftType() {
        assertEquals(CookingMachineFamily.CAMPFIRE,
                CookingMachineFamily.fromRecipeTypeId(new ResourceLocation("minecraft", "campfire_cooking")));
        assertEquals(CookingMachineFamily.UNKNOWN,
                CookingMachineFamily.fromRecipeTypeId(new ResourceLocation("brickfurnace", "campfire_cooking")));
    }

    @Test
    void brickCompatScalingTruncatesAndRejectsZero() {
        assertEquals(50, BrickFurnaceCompat.scale(100, 0.5));
        assertEquals(150, BrickFurnaceCompat.scale(100, 1.5));
        assertEquals(0, BrickFurnaceCompat.scale(100, 0));
    }

    @Test
    void cookingWrapperExposesParallelCapabilityBeforeMachineSelection() {
        CookingMachineBatchDelegate delegate = new CookingMachineBatchDelegate();

        assertEquals(BatchConcurrencyCapabilities.machineSlot(),
                delegate.concurrencyCapabilities());
    }

    @Test
    void brickBufferIsExplicitlyScopedAndDefaultsToEnabled() {
        for (String path : new String[]{"brick_furnace", "brick_blast_furnace", "brick_smoker"}) {
            assertTrue(VanillaMachineBatchDelegate.supportsFurnaceBufferTarget(
                    new ResourceLocation("brickfurnace", path), true));
        }
        assertTrue(VanillaMachineBatchDelegate.supportsFurnaceBufferTarget(
                new ResourceLocation("minecraft", "furnace"), false));
        assertFalse(VanillaMachineBatchDelegate.supportsFurnaceBufferTarget(
                new ResourceLocation("other", "furnace"), false));
        assertFalse(VanillaMachineBatchDelegate.supportsFurnaceBufferTarget(
                new ResourceLocation("minecraft", "furnace"), true));
        assertFalse(VanillaMachineBatchDelegate.supportsFurnaceBufferTarget(
                new ResourceLocation("brickfurnace", "brick_furnace"), false));
        assertTrue(RSIntegrationConfig.ENABLE_BRICK_FURNACE_INPUT_BUFFER.getDefault());
        assertEquals(64, RSIntegrationConfig.BRICK_FURNACE_INPUT_BUFFER_LIMIT.getDefault());
    }
}
