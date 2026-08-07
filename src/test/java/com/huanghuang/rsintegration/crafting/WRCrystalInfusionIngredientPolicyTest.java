package com.huanghuang.rsintegration.crafting;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WRCrystalInfusionIngredientPolicyTest {

    private static final ResourceLocation RECIPE = new ResourceLocation(
            "wizards_reborn", "crystal_infusion/irons_spellbooks_gold_crown");
    private static final ResourceLocation CROWN = new ResourceLocation(
            "irons_spellbooks", "tarnished_helmet");

    @Test
    void onlyGoldCrownInfusionTreatsTarnishedHelmetAsNbtInsensitive() {
        assertTrue(CraftPacketUtils.isNbtInsensitiveWRCrystalInfusion(RECIPE, CROWN));
        assertTrue(CraftPacketUtils.isNbtInsensitiveWRCrystalInfusion(RECIPE, null));
        assertFalse(CraftPacketUtils.isNbtInsensitiveWRCrystalInfusion(
                new ResourceLocation("wizards_reborn", "crystal_infusion/other"), CROWN));
        assertFalse(CraftPacketUtils.isNbtInsensitiveWRCrystalInfusion(RECIPE,
                new ResourceLocation("minecraft", "iron_helmet")));
    }
}
