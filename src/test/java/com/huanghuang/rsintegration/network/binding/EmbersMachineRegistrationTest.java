package com.huanghuang.rsintegration.network.binding;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.mods.embers.EmbersMachineHints;
import com.huanghuang.rsintegration.mods.embers.EmbersMachinesRSModule;
import com.huanghuang.rsintegration.util.ModIds;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class EmbersMachineRegistrationTest {
    @BeforeAll
    static void register() {
        EmbersMachinesRSModule.INSTANCE.registerModType();
    }

    @Test
    void jeiAndRecipeClassesSelectTheCorrectMachine() {
        assertMapping(ModIds.ID_EMBERS_MELTER, "melter", "melting", "MeltingRecipe");
        assertMapping(ModIds.ID_EMBERS_MIXER, "mixer_centrifuge", "mixing", "MixingRecipe");
        assertMapping(ModIds.ID_EMBERS_STAMPER, "stamper", "stamping", "StampingRecipe");
    }

    private static void assertMapping(String id, String block, String category, String recipeClass) {
        ModType type = ModType.byId(id);
        assertSame(type, ModType.fromBlockKey(id + "||block.embers." + block));
        assertEquals(id, ModType.filterForJeiUid("embers:" + category));
        assertSame(type, ModType.findByRecipeClass("com.rekindled.embers.recipe." + recipeClass));
        assertNull(AltarBindingRegistry.normalizeSubType(category, type));
        assertNull(AltarBindingRegistry.normalizeSubType("ingots", type));
        assertFalse(EmbersMachineHints.tooltipKeys(type).isEmpty());
    }
}
