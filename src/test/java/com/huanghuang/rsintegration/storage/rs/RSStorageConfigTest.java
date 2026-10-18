package com.huanghuang.rsintegration.storage.rs;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.huanghuang.rsintegration.config.RSStorageConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RSStorageConfigTest {
    @Test
    void startupDefaultsAndLoadedSwitchesHaveTheSamePolicy() {
        assertTrue(RSStorageConfig.enabled(RSStorageConfig.MERGE_CONNECTION_REBUILDS));
        assertTrue(RSStorageConfig.enabled(RSStorageConfig.VERIFY_SAVES));
        assertTrue(RSStorageConfig.enabled(RSStorageConfig.REFRESH_OPEN_GRIDS));
        assertTrue(RSStorageConfig.enabled(RSStorageConfig.EXPAND_GRID_TRANSFER));
        assertTrue(RSStorageConfig.enabled(RSStorageConfig.CHECK_FLUID_CONTAINER));
        assertTrue(RSStorageConfig.enabled(RSStorageConfig.UNIFIED_GRID));
        assertTrue(RSStorageConfig.enabled(RSStorageConfig.UNIFIED_DISK));
        assertTrue(RSStorageConfig.enabled(RSStorageConfig.FAST_DISK_MANIPULATOR));
        assertEquals(262144, RSStorageConfig.diskLimit(RSStorageConfig.DISK_ITEM_ENTRIES));
        assertEquals(262144, RSStorageConfig.diskLimit(RSStorageConfig.DISK_FLUID_ENTRIES));
        assertEquals(100, RSStorageConfig.transferParts());
        assertEquals(1000, RSStorageConfig.diskManipulatorBudgetMicros());
        assertEquals(1024, RSStorageConfig.diskManipulatorMaxOperationsPerTick());
        assertEquals(Integer.MAX_VALUE, RSStorageConfig.diskManipulatorMaxItemsPerOperation());
        assertEquals(20, RSStorageConfig.diskManipulatorNoProgressCooldownTicks());

        CommentedConfig config = CommentedConfig.inMemory();
        RSStorageConfig.SPEC.correct(config);
        config.set("storage.mergeConnectionRebuilds", false);
        config.set("storage.verifySaves", false);
        config.set("storage.refreshOpenGrids", false);
        config.set("storage.expandGridTransfer", false);
        config.set("storage.checkFluidContainer", false);
        config.set("unifiedGrid.enabled", false);
        config.set("unifiedDisk.enabled", false);
        config.set("unifiedDisk.maxItemEntries", 50000);
        config.set("storage.diskManipulator.enabled", false);
        config.set("storage.diskManipulator.budgetMicros", 250);
        config.set("storage.diskManipulator.maxOperationsPerTick", 32);
        config.set("storage.diskManipulator.maxItemsPerOperation", 1024);
        config.set("storage.diskManipulator.noProgressCooldownTicks", 5);
        config.set("storage.gridTransferParts", 32);
        try {
            RSStorageConfig.SPEC.setConfig(config);
            assertFalse(RSStorageConfig.enabled(RSStorageConfig.MERGE_CONNECTION_REBUILDS));
            assertFalse(RSStorageConfig.enabled(RSStorageConfig.VERIFY_SAVES));
            assertFalse(RSStorageConfig.enabled(RSStorageConfig.REFRESH_OPEN_GRIDS));
            assertFalse(RSStorageConfig.enabled(RSStorageConfig.EXPAND_GRID_TRANSFER));
            assertFalse(RSStorageConfig.enabled(RSStorageConfig.CHECK_FLUID_CONTAINER));
            assertFalse(RSStorageConfig.enabled(RSStorageConfig.UNIFIED_GRID));
            assertFalse(RSStorageConfig.enabled(RSStorageConfig.UNIFIED_DISK));
            assertFalse(RSStorageConfig.enabled(RSStorageConfig.FAST_DISK_MANIPULATOR));
            assertEquals(50000, RSStorageConfig.diskLimit(RSStorageConfig.DISK_ITEM_ENTRIES));
            assertEquals(32, RSStorageConfig.transferParts());
            assertEquals(250, RSStorageConfig.diskManipulatorBudgetMicros());
            assertEquals(32, RSStorageConfig.diskManipulatorMaxOperationsPerTick());
            assertEquals(1024, RSStorageConfig.diskManipulatorMaxItemsPerOperation());
            assertEquals(5, RSStorageConfig.diskManipulatorNoProgressCooldownTicks());
        } finally {
            RSStorageConfig.SPEC.setConfig(null);
        }
        assertEquals(100, RSStorageConfig.transferParts());
    }
}
