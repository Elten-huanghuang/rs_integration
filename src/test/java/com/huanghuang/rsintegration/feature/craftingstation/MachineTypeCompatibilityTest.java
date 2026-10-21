package com.huanghuang.rsintegration.feature.craftingstation;

import com.huanghuang.rsintegration.util.ModIds;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MachineTypeCompatibilityTest {
    @Test
    void exactMachineTypesRemainCompatible() {
        assertTrue(MachineTypeCompatibility.isCompatible(
                "smithing", "smithing"));
        assertFalse(MachineTypeCompatibility.isCompatible(
                "smithing", "vanilla_anvil"));
    }

    @Test
    void ironFurnacesContinueToSatisfyTheirVanillaEquivalent() {
        assertTrue(MachineTypeCompatibility.isCompatible(
                "vanilla_furnace", "ironfurnaces_furnace"));
        assertTrue(MachineTypeCompatibility.isCompatible(
                "vanilla_blast_furnace", "ironfurnaces_blast_furnace"));
        assertTrue(MachineTypeCompatibility.isCompatible(
                "vanilla_smoker", "ironfurnaces_smoker"));
        assertFalse(MachineTypeCompatibility.isCompatible(
                "vanilla_furnace", "ironfurnaces_smoker"));
    }

    @Test
    void sharedCookingPotRecipesUseOneCompatibilityRule() {
        assertTrue(MachineTypeCompatibility.isCompatible(
                ModIds.ID_FC_COOKING_POT,
                ModIds.ID_CANDLELIGHT_COOKING_POT));
        assertTrue(MachineTypeCompatibility.isCompatible(
                ModIds.ID_BAKERY_COOKING_POT,
                ModIds.ID_FC_COOKING_POT));
        assertFalse(MachineTypeCompatibility.isCompatible(
                ModIds.ID_CANDLELIGHT_COOKING_POT,
                ModIds.ID_BAKERY_COOKING_POT));
    }
}
