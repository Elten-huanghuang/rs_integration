package com.huanghuang.rsintegration.mods.youkaishomecoming;

import com.huanghuang.rsintegration.util.ModIds;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class YoukaiRegistryIdsTest {

    @Test
    void integrationIsEnabledByEitherModId() {
        assertEquals(List.of(ModIds.YOUKAISHOMECOMING, ModIds.YOUKAISFEASTS),
                YoukaisHomecomingRSModule.INSTANCE.modIds());
    }

    @Test
    void acceptsBothRegistryNamespaces() {
        assertTrue(YoukaiRegistryIds.isSupportedNamespace(ModIds.YOUKAISHOMECOMING));
        assertTrue(YoukaiRegistryIds.isSupportedNamespace(ModIds.YOUKAISFEASTS));
        assertFalse(YoukaiRegistryIds.isSupportedNamespace("farmersdelight"));
    }

    @Test
    void buildsEquivalentMachineIdsForBothMods() {
        assertEquals("youkaishomecoming:steamer_pot",
                YoukaiRegistryIds.stringId(ModIds.YOUKAISHOMECOMING, "steamer_pot"));
        assertEquals(new ResourceLocation("youkaisfeasts", "steamer_pot"),
                YoukaiRegistryIds.id(ModIds.YOUKAISFEASTS, "steamer_pot"));
        assertTrue(YoukaiRegistryIds.matches(
                new ResourceLocation("youkaisfeasts", "steamer_lid"), "steamer_lid"));
    }
}
