package com.huanghuang.rsintegration.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TypedPreviewTimeoutConfigTest {
    @Test
    void legacyDefaultMigratesWithoutOverwritingCustomBudgets() {
        assertEquals(CraftingPlanningConfig.DEFAULT_TYPED_PREVIEW_TIMEOUT_MS,
                RSIntegrationConfig.migrateTypedPreviewTimeoutMs(3, 50));
        assertEquals(50, RSIntegrationConfig.migrateTypedPreviewTimeoutMs(4, 50));
        assertEquals(CraftingPlanningConfig.DEFAULT_TYPED_PREVIEW_TIMEOUT_MS,
                RSIntegrationConfig.migrateTypedPreviewTimeoutMs(5, 400));
        assertEquals(125, RSIntegrationConfig.migrateTypedPreviewTimeoutMs(5, 125));
        assertEquals(125, RSIntegrationConfig.migrateTypedPreviewTimeoutMs(3, 125));
    }

    @Test
    void purePlanningDefaultMigratesWithoutOverwritingCustomBudgets() {
        assertEquals(CraftingPlanningConfig.DEFAULT_PURE_TIMEOUT_MS,
                RSIntegrationConfig.migratePurePlanningTimeoutMs(5, 500));
        assertEquals(750, RSIntegrationConfig.migratePurePlanningTimeoutMs(5, 750));
        assertEquals(500, RSIntegrationConfig.migratePurePlanningTimeoutMs(6, 500));
    }
}
