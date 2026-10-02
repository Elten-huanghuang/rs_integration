package com.huanghuang.rsintegration.config;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.huanghuang.rsintegration.mods.common.MachineWaterSupply;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FreeWaterMachinesConfigTest {
    @Test
    void defaultsCoverEveryIntegratedWaterMachineWithoutDuplicates() {
        assertEquals(List.of("farmersrespite_kettle", "youkaishomecoming_kettle", "youkaishomecoming_ferment",
                "youkaishomecoming_moka", "youkaishomecoming_steamer", "irons_spellbooks_alchemist_cauldron",
                "eidolon_crucible", "botania_petal_apothecary"), RSIntegrationConfig.DEFAULT_FREE_WATER_MACHINES);
        assertEquals(RSIntegrationConfig.DEFAULT_FREE_WATER_MACHINES.size(),
                new HashSet<>(RSIntegrationConfig.DEFAULT_FREE_WATER_MACHINES).size());
    }

    @Test
    void startupDefaultsAreAvailableBeforeTheServerConfigLoads() {
        assertTrue(MachineWaterSupply.isFree("eidolon_crucible"));
        assertFalse(MachineWaterSupply.isFree("unknown_machine"));
    }

    @Test
    void emptyWhitelistIsNotCorrectedBackToFreeWaterDefaults() {
        assertConfigured(List.of());
    }

    @Test
    void customWhitelistOnlyEnablesItsExactMachineIds() {
        assertConfigured(List.of("youkaishomecoming_kettle"));
        assertFalse(MachineWaterSupply.isFree("youkaishomecoming", List.of("youkaishomecoming_kettle")));
    }

    private void assertConfigured(List<String> whitelist) {
        CommentedConfig config = CommentedConfig.inMemory();
        RSIntegrationConfig.SERVER_SPEC.correct(config);
        config.set("autoCrafting.freeWaterMachines", whitelist);
        RSIntegrationConfig.SERVER_SPEC.correct(config);
        try {
            RSIntegrationConfig.SERVER_SPEC.setConfig(config);
            assertEquals(whitelist, RSIntegrationConfig.FREE_WATER_MACHINES.get());
            for (String machine : RSIntegrationConfig.DEFAULT_FREE_WATER_MACHINES) {
                assertEquals(whitelist.contains(machine), MachineWaterSupply.isFree(machine));
            }
        } finally {
            RSIntegrationConfig.SERVER_SPEC.setConfig(null);
        }
    }
}
