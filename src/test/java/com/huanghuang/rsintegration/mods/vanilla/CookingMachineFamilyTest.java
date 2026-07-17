package com.huanghuang.rsintegration.mods.vanilla;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
