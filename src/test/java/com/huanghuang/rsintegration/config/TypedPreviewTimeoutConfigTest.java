package com.huanghuang.rsintegration.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TypedPreviewTimeoutConfigTest {
    @Test
    void legacyDefaultMigratesWithoutOverwritingCustomBudgets() {
        assertEquals(200, RSIntegrationConfig.migrateTypedPreviewTimeoutMs(3, 50));
        assertEquals(50, RSIntegrationConfig.migrateTypedPreviewTimeoutMs(4, 50));
        assertEquals(125, RSIntegrationConfig.migrateTypedPreviewTimeoutMs(3, 125));
    }
}
