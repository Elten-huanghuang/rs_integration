package com.huanghuang.rsintegration.mods.biomancy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BiomancyFuelPolicyTest {
    @Test
    void higherNutrientValueWinsRegardlessOfRegistryOrder() {
        assertTrue(BiomancyFuelPolicy.compare(27, "biomancy:nutrient_bar", 3, "biomancy:nutrient_paste") < 0);
        assertTrue(BiomancyFuelPolicy.compare(3, "biomancy:nutrient_paste", 27, "biomancy:nutrient_bar") > 0);
    }

    @Test
    void sameValueUsesStableItemIdOrder() {
        assertTrue(BiomancyFuelPolicy.compare(4, "minecraft:apple", 4, "minecraft:bread") < 0);
        assertEquals(0, BiomancyFuelPolicy.compare(4, "minecraft:apple", 4, "minecraft:apple"));
    }

    @Test
    void fuelCountCoversDeficitAndRespectsStackCapacity() {
        assertEquals(4, BiomancyFuelPolicy.requiredItems(10, 3, 64));
        assertEquals(3, BiomancyFuelPolicy.requiredItems(9, 3, 64));
        assertEquals(64, BiomancyFuelPolicy.requiredItems(1000, 3, 64));
        assertEquals(64, BiomancyFuelPolicy.requiredItems(Integer.MAX_VALUE, 3, 64));
        assertEquals(0, BiomancyFuelPolicy.requiredItems(0, 3, 64));
        assertEquals(0, BiomancyFuelPolicy.requiredItems(10, 0, 64));
    }
}
