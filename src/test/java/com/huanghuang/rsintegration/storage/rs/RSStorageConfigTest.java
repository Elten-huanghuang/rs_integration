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
        assertEquals(100, RSStorageConfig.transferParts());

        CommentedConfig config = CommentedConfig.inMemory();
        RSStorageConfig.SPEC.correct(config);
        config.set("storage.mergeConnectionRebuilds", false);
        config.set("storage.verifySaves", false);
        config.set("storage.refreshOpenGrids", false);
        config.set("storage.expandGridTransfer", false);
        config.set("storage.checkFluidContainer", false);
        config.set("storage.gridTransferParts", 32);
        try {
            RSStorageConfig.SPEC.setConfig(config);
            assertFalse(RSStorageConfig.enabled(RSStorageConfig.MERGE_CONNECTION_REBUILDS));
            assertFalse(RSStorageConfig.enabled(RSStorageConfig.VERIFY_SAVES));
            assertFalse(RSStorageConfig.enabled(RSStorageConfig.REFRESH_OPEN_GRIDS));
            assertFalse(RSStorageConfig.enabled(RSStorageConfig.EXPAND_GRID_TRANSFER));
            assertFalse(RSStorageConfig.enabled(RSStorageConfig.CHECK_FLUID_CONTAINER));
            assertEquals(32, RSStorageConfig.transferParts());
        } finally {
            RSStorageConfig.SPEC.setConfig(null);
        }
        assertEquals(100, RSStorageConfig.transferParts());
    }
}
