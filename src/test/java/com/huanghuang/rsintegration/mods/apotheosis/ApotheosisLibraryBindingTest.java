package com.huanghuang.rsintegration.mods.apotheosis;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApotheosisLibraryBindingTest {

    @Test
    void recognizesBothLibraryRegistryKeys() {
        assertTrue(ApotheosisLibraryBinding.isLibrary("apotheosis:library"));
        assertTrue(ApotheosisLibraryBinding.isLibrary("apotheosis:ender_library"));
        assertFalse(ApotheosisLibraryBinding.isLibrary("minecraft:fletching_table"));
        assertFalse(ApotheosisLibraryBinding.isLibrary(null));
    }

    @Test
    void ordinaryAndEnderLibrariesRemainDistinct() {
        assertTrue(ApotheosisLibraryBinding.matchesSavedBlock(
                "apotheosis:library", new ResourceLocation("apotheosis", "library")));
        assertTrue(ApotheosisLibraryBinding.matchesSavedBlock(
                "apotheosis:ender_library", new ResourceLocation("apotheosis", "ender_library")));
        assertFalse(ApotheosisLibraryBinding.matchesSavedBlock(
                "apotheosis:library", new ResourceLocation("apotheosis", "ender_library")));
        assertFalse(ApotheosisLibraryBinding.matchesSavedBlock(
                "apotheosis:ender_library", new ResourceLocation("apotheosis", "library")));
    }
}
