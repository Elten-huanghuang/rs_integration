package com.huanghuang.rsintegration.mods.goety;

import com.huanghuang.rsintegration.ModType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GoetyBindingRulesTest {

    @BeforeAll
    static void registerGoetyMachineTypes() {
        GoetyRSModule.INSTANCE.registerModType();
    }

    @Test
    void ritualAndBrazierUseDifferentExecutionTypes() {
        assertTrue(ModType.fromBlockKey("goety||block.goety.necro_brazier")
                == ModType.byId(GoetyRSModule.BRAZIER_TYPE_ID));
        assertTrue(ModType.fromBlockKey("goety_altar||block.goety.dark_altar")
                == ModType.byId("goety"));
        assertTrue(ModType.findByRecipeClass(
                "com.Polarice3.Goety.common.crafting.BrazierRecipe")
                == ModType.byId(GoetyRSModule.BRAZIER_TYPE_ID));
        assertTrue(ModType.findByRecipeClass(
                "com.Polarice3.Goety.common.crafting.RitualRecipe")
                == ModType.byId("goety"));
    }

    @Test
    void brazierRecipeDoesNotMatchDarkAltarPrefix() {
        assertTrue(GoetyBindingRules.matches(
                "goety||block.goety.necro_brazier", "goety:necro_brazier", "goety"));
        assertFalse(GoetyBindingRules.matches(
                "goety_altar||block.goety.dark_altar", "goety:dark_altar", "goety"));
    }

    @Test
    void ritualRecipeDoesNotMatchNecroBrazierPrefix() {
        assertTrue(GoetyBindingRules.matches(
                "goety_altar||block.goety.dark_altar", "goety:dark_altar", "goety_altar"));
        assertFalse(GoetyBindingRules.matches(
                "goety||block.goety.necro_brazier", "goety:necro_brazier", "goety_altar"));
    }

    @Test
    void legacyBindingsUseMachineIdentityInsteadOfBroadModIdMatch() {
        assertTrue(GoetyBindingRules.matches(
                "block.goety.necro_brazier", "goety:necro_brazier", "goety"));
        assertTrue(GoetyBindingRules.matches(
                "block.goety.dark_altar", "goety:dark_altar", "goety_altar"));
        assertFalse(GoetyBindingRules.matches(
                "block.goety.dark_altar", "goety:dark_altar", "goety"));
    }
}
