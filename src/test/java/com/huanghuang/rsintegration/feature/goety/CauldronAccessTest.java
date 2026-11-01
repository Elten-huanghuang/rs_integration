package com.huanghuang.rsintegration.feature.goety;

import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.feature.goety.brewing.CauldronAccess;
import com.huanghuang.rsintegration.mods.common.MachineWaterSupply;
import net.minecraftforge.common.ForgeConfigSpec;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CauldronAccessTest {
    @Test
    void emptyAndPlainWaterCauldronsAllowTheSameIdleEntry() {
        // 空锅与清水锅都是 IDLE；水位不应成为进入编辑器的限制。
        assertTrue(CauldronAccess.canOpen("IDLE", false, false));
        assertFalse(CauldronAccess.canOpen("IDLE", true, false));
        assertFalse(CauldronAccess.canOpen("IDLE", false, true));
    }

    @Test
    void potionWasteAndPreparedOrRunningRecipesCannotBeReopened() {
        for (String mode : List.of("BREWING", "CRAFTING", "COMPLETED", "CRAFTED", "FAILED")) {
            assertFalse(CauldronAccess.canOpen(mode, false, false), mode);
            assertFalse(CauldronAccess.canOpen(mode, true, true), mode);
        }
    }

    @Test
    void cauldronUsesTheSharedFreeWaterDefaults() {
        assertTrue(MachineWaterSupply.isFree("goety_witch_cauldron",
                RSIntegrationConfig.DEFAULT_FREE_WATER_MACHINES));
        assertFalse(MachineWaterSupply.isFree("goety_witch_cauldron", List.of()));
    }

    @Test
    void bothPreviousDefaultListsGainCauldronWaterWithoutChangingCustomLists() {
        var defaults = RSIntegrationConfig.DEFAULT_FREE_WATER_MACHINES;
        assertEquals(defaults, RSIntegrationConfig.migrateFreeWaterMachines(6,
                defaults.subList(0, defaults.size() - 2)));
        assertEquals(defaults, RSIntegrationConfig.migrateFreeWaterMachines(7,
                defaults.subList(0, defaults.size() - 1)));
        assertEquals(List.of(), RSIntegrationConfig.migrateFreeWaterMachines(7, List.of()));
        var custom = List.of("eidolon_crucible");
        assertEquals(custom, RSIntegrationConfig.migrateFreeWaterMachines(7, custom));
        assertEquals(custom, RSIntegrationConfig.migrateFreeWaterMachines(8, custom));
    }

    @Test
    void sharedVirtualCandleConfigurationAccepts1024ButRejectsLargerValues() {
        ForgeConfigSpec.ValueSpec spec = RSIntegrationConfig.COMMON_SPEC.getSpec()
                .get(RSIntegrationConfig.GOETY_BRAZIER_VIRTUAL_CANDLE_COUNT.getPath());
        assertTrue(spec.test(1024));
        assertFalse(spec.test(1025));
        assertFalse(spec.test(0));
    }
}
