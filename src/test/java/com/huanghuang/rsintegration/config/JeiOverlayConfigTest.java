package com.huanghuang.rsintegration.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JeiOverlayConfigTest {
    @Test
    void inventoryAndShortageScalesHaveIndependentDefaults() {
        assertEquals(0.70D, RSIntegrationConfig.JEI_NETWORK_OVERLAY_SCALE.getDefault());
        assertEquals(0.75D,
                RSIntegrationConfig.JEI_CRAFTING_SHORTAGE_OVERLAY_SCALE.getDefault());
    }
}
