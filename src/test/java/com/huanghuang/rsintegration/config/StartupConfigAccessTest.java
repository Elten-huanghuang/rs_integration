package com.huanghuang.rsintegration.config;

import com.electronwill.nightconfig.core.CommentedConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StartupConfigAccessTest {
    @Test
    void unloadedStartupConfigUsesDeclaredDefaults() {
        assertFalse(RSIntegrationConfig.enabled(RSIntegrationConfig.COMMON_SPEC,
                RSIntegrationConfig.ENABLE_RS_SIDE_PANEL));
        assertTrue(RSIntegrationConfig.enabled(RSIntegrationConfig.COMMON_SPEC,
                RSIntegrationConfig.ENABLE_CONTAINER_TRANSFER));
        assertEquals(70, RSIntegrationConfig.keyCode(RSIntegrationConfig.SERVER_SPEC,
                RSIntegrationConfig.CONTAINER_TRANSFER_KEY));
        assertEquals(89, RSIntegrationConfig.keyCode(RSIntegrationConfig.SERVER_SPEC,
                RSIntegrationConfig.RS_SIDE_PANEL_KEY));
    }

    @Test
    void loadedStartupConfigUsesConfiguredValues() {
        CommentedConfig common = CommentedConfig.inMemory();
        RSIntegrationConfig.COMMON_SPEC.correct(common);
        common.set("sidePanel.enableRSSidePanel", true);
        common.set("containerTransfer.enableContainerTransfer", false);

        CommentedConfig server = CommentedConfig.inMemory();
        RSIntegrationConfig.SERVER_SPEC.correct(server);
        server.set("containerTransfer.containerTransferKey", 71);
        server.set("sidePanel.rsSidePanelKey", 90);

        try {
            RSIntegrationConfig.COMMON_SPEC.setConfig(common);
            RSIntegrationConfig.SERVER_SPEC.setConfig(server);
            assertTrue(RSIntegrationConfig.enabled(RSIntegrationConfig.COMMON_SPEC,
                    RSIntegrationConfig.ENABLE_RS_SIDE_PANEL));
            assertFalse(RSIntegrationConfig.enabled(RSIntegrationConfig.COMMON_SPEC,
                    RSIntegrationConfig.ENABLE_CONTAINER_TRANSFER));
            assertEquals(71, RSIntegrationConfig.keyCode(RSIntegrationConfig.SERVER_SPEC,
                    RSIntegrationConfig.CONTAINER_TRANSFER_KEY));
            assertEquals(90, RSIntegrationConfig.keyCode(RSIntegrationConfig.SERVER_SPEC,
                    RSIntegrationConfig.RS_SIDE_PANEL_KEY));
        } finally {
            RSIntegrationConfig.COMMON_SPEC.setConfig(null);
            RSIntegrationConfig.SERVER_SPEC.setConfig(null);
        }
    }
}
