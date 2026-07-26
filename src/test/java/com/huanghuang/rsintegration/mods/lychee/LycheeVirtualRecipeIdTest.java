package com.huanghuang.rsintegration.mods.lychee;

import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LycheeVirtualRecipeIdTest {

    @Test
    void acceptsOnlyReviewedPackRecipeIds() {
        assertTrue(LycheeVirtualRecipeHandler.isSupportedId(
                new ResourceLocation("crafttweaker", "avaritia.diamond_lattice.1")));
        assertTrue(LycheeVirtualRecipeHandler.isSupportedId(
                new ResourceLocation("crafttweaker", "avaritia.diamond_lattice.12")));
        assertTrue(LycheeVirtualRecipeHandler.isSupportedId(
                new ResourceLocation("crafttweaker", "refinedstorage.basic_processor")));
        assertTrue(LycheeVirtualRecipeHandler.isSupportedId(
                new ResourceLocation("crafttweaker", "avaritia.eternal_singularity")));
        assertTrue(LycheeVirtualRecipeHandler.isSupportedId(
                new ResourceLocation("crafttweaker", "deep_aether.sterling_aercloud")));

        assertFalse(LycheeVirtualRecipeHandler.isSupportedId(
                new ResourceLocation("crafttweaker", "avaritia.diamond_lattice.5")));
        assertFalse(LycheeVirtualRecipeHandler.isSupportedId(
                new ResourceLocation("crafttweaker", "avaritia.diamond_lattice.13")));
        assertFalse(LycheeVirtualRecipeHandler.isSupportedId(
                new ResourceLocation("other", "avaritia.diamond_lattice.1")));
        assertFalse(LycheeVirtualRecipeHandler.isSupportedId(
                new ResourceLocation("crafttweaker", "unreviewed.item_inside")));
    }

    @Test
    void acceptsVanillaSerializedPlainPowderSnowPredicate() {
        assertTrue(LycheeVirtualRecipeHandler.isPlainPowderSnowJson(JsonParser.parseString(
                "{\"blocks\":[\"minecraft:powder_snow\"],\"nbt\":null,\"state\":null}")));
    }

    @Test
    void rejectsAdditionalPowderSnowPredicateConstraints() {
        assertFalse(LycheeVirtualRecipeHandler.isPlainPowderSnowJson(JsonParser.parseString(
                "{\"blocks\":[\"minecraft:powder_snow\"],\"state\":{\"layers\":\"1\"}}")));
        assertFalse(LycheeVirtualRecipeHandler.isPlainPowderSnowJson(JsonParser.parseString(
                "{\"blocks\":[\"minecraft:powder_snow\",\"minecraft:snow_block\"]}")));
        assertFalse(LycheeVirtualRecipeHandler.isPlainPowderSnowJson(JsonParser.parseString(
                "{\"blocks\":[\"minecraft:powder_snow\"],\"unknown\":true}")));
    }

    @Test
    void acceptsOnlyReviewedSourceFluids() {
        assertTrue(LycheeVirtualRecipeHandler.isSupportedSubstrateJson(JsonParser.parseString(
                        "{\"blocks\":[\"locusazzurro_icaruswings:greek_fire\"],"
                                + "\"state\":{\"level\":\"0\"},\"nbt\":null}"),
                "locusazzurro_icaruswings:greek_fire", true));
        assertTrue(LycheeVirtualRecipeHandler.isSupportedSubstrateJson(JsonParser.parseString(
                        "{\"blocks\":[\"embers:dwarven_oil_block\"],"
                                + "\"state\":{\"level\":\"0\"},\"nbt\":null}"),
                "embers:dwarven_oil_block", true));
        assertTrue(LycheeVirtualRecipeHandler.isSupportedSubstrateJson(JsonParser.parseString(
                        "{\"blocks\":[\"deep_aether:poison\"],"
                                + "\"state\":{\"level\":\"0\"},\"nbt\":null}"),
                "deep_aether:poison", true));
        assertFalse(LycheeVirtualRecipeHandler.isSupportedSubstrateJson(JsonParser.parseString(
                        "{\"blocks\":[\"embers:dwarven_oil_block\"],"
                                + "\"state\":{\"level\":\"1\"}}"),
                "embers:dwarven_oil_block", true));
        assertFalse(LycheeVirtualRecipeHandler.isSupportedSubstrateJson(JsonParser.parseString(
                        "{\"blocks\":[\"deep_aether:poison\"],"
                                + "\"state\":{\"level\":\"2\"}}"),
                "deep_aether:poison", true));
    }

    @Test
    void mapsEachRecipeFamilyToItsOwnCatalyst() {
        assertEquals(LycheeVirtualCatalysts.POWDER_SNOW_BUCKET,
                LycheeVirtualRecipeHandler.requiredCatalystMask(
                        new ResourceLocation("crafttweaker", "avaritia.diamond_lattice.1")));
        assertEquals(LycheeVirtualCatalysts.GREEK_FIRE_BUCKET,
                LycheeVirtualRecipeHandler.requiredCatalystMask(
                        new ResourceLocation("crafttweaker", "refinedstorage.basic_processor")));
        assertEquals(LycheeVirtualCatalysts.DWARVEN_OIL_BUCKET,
                LycheeVirtualRecipeHandler.requiredCatalystMask(
                        new ResourceLocation("crafttweaker", "avaritia.eternal_singularity")));
        assertEquals(LycheeVirtualCatalysts.DEEP_AETHER_POISON_BUCKET,
                LycheeVirtualRecipeHandler.requiredCatalystMask(
                        new ResourceLocation("crafttweaker", "deep_aether.sterling_aercloud")));
    }
}
