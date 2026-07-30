package com.huanghuang.rsintegration.config;

import com.electronwill.nightconfig.core.CommentedConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RepeatCountConfigTest {

    @Test
    void defaultAndAbsoluteMaximumAllowMoreThanOneStack() {
        assertEquals(1024, RSIntegrationConfig.REPEAT_COUNT_MAX.getDefault());
        assertTrue(RSIntegrationConfig.REPEAT_COUNT_MAX.getDefault() > 64);
        assertEquals(1024, RSIntegrationConfig.REPEAT_COUNT_ABSOLUTE_MAX);
    }

    @Test
    void legacyDefaultMigratesOnceWithoutOverwritingCustomLimits() {
        assertEquals(1024, RSIntegrationConfig.migrateRepeatCountMax(1, 64));
        assertEquals(64, RSIntegrationConfig.migrateRepeatCountMax(2, 64));
        assertEquals(128, RSIntegrationConfig.migrateRepeatCountMax(1, 128));
    }

    @Test
    void forgeSpecClampsRequestsToSafeBounds() {
        CommentedConfig config = defaultConfig();
        config.set(RSIntegrationConfig.REPEAT_COUNT_MAX.getPath(), 4096);

        assertTrue(RSIntegrationConfig.SERVER_SPEC.correct(config) != 0);
        RSIntegrationConfig.SERVER_SPEC.setConfig(config);
        assertEquals(1024, RSIntegrationConfig.REPEAT_COUNT_MAX.get());
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
