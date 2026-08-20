package com.huanghuang.rsintegration.config;

import com.electronwill.nightconfig.core.CommentedConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VillagerTradeLockConfigTest {

    @Test
    void tradeLockIsEnabledByDefault() {
        assertTrue(RSIntegrationConfig.ENABLE_VILLAGER_TRADE_LOCK.getDefault());
    }

    @Test
    void serverConfigCanDisableTradeLock() {
        CommentedConfig config = defaultConfig();
        config.set(RSIntegrationConfig.ENABLE_VILLAGER_TRADE_LOCK.getPath(), false);
        RSIntegrationConfig.SERVER_SPEC.setConfig(config);

        assertFalse(RSIntegrationConfig.ENABLE_VILLAGER_TRADE_LOCK.get());
    }

    @AfterEach
    void restoreDefaults() {
        RSIntegrationConfig.SERVER_SPEC.setConfig(defaultConfig());
    }

    private static CommentedConfig defaultConfig() {
        CommentedConfig config = CommentedConfig.inMemory();
        RSIntegrationConfig.SERVER_SPEC.correct(config);
        return config;
    }
}
