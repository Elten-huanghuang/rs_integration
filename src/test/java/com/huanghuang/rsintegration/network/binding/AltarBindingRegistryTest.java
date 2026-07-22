package com.huanghuang.rsintegration.network.binding;

import com.huanghuang.rsintegration.ModType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AltarBindingRegistryTest {

    @BeforeAll
    static void registerGoetyType() {
        if (ModType.byId("goety") == ModType.GENERIC) {
            ModType.register("goety", new String[0], new String[]{"goety"},
                    new String[]{"goety", "goety_altar", "goety_component"}, () -> null);
        }
        if (ModType.byId("goety_cursed_infuser") == ModType.GENERIC) {
            ModType.register("goety_cursed_infuser", new String[0], new String[]{"goety"},
                    new String[]{"goety_cursed_infuser"}, () -> null);
        }
    }

    @Test
    void goetyComponentsAreNotExecutableMachines() {
        ModType goety = ModType.byId("goety");

        assertTrue(AltarBindingRegistry.isExecutableBinding(goety, "goety"));
        assertTrue(AltarBindingRegistry.isExecutableBinding(goety, "goety_altar"));
        assertFalse(AltarBindingRegistry.isExecutableBinding(goety, "goety_component"));
        assertFalse(AltarBindingRegistry.isExecutableBinding(
                goety, "goety_component||block.goety.cursed_cage"));
        assertFalse(AltarBindingRegistry.isExecutableBinding(
                goety, "goety_component||block.goety.soul_candlestick"));
    }

    @Test
    void cursedInfuserRecipeFolderIsNotTreatedAsMachineSubtype() {
        ModType infuser = ModType.byId("goety_cursed_infuser");

        assertNull(AltarBindingRegistry.normalizeSubType("shade", infuser));
    }
}
