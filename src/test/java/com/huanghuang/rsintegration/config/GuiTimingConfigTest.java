package com.huanghuang.rsintegration.config;

import com.electronwill.nightconfig.core.CommentedConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GuiTimingConfigTest {
    @Test
    void defaultsMatchForgeSpecs() {
        assertEquals(GuiTimingConfig.DEFAULT_OPEN_RATE_LIMIT_MS,
                RSIntegrationConfig.GUI_OPEN_RATE_LIMIT_MS.getDefault());
        assertEquals(GuiTimingConfig.DEFAULT_NAVIGATION_TIMEOUT_MS,
                RSIntegrationConfig.SIDE_PANEL_NAVIGATION_TIMEOUT_MS.getDefault());
    }

    @Test
    void loadsMinimumAndMaximumValues() {
        CommentedConfig server = defaultServerConfig();
        CommentedConfig client = defaultClientConfig();
        server.set(RSIntegrationConfig.GUI_OPEN_RATE_LIMIT_MS.getPath(),
                GuiTimingConfig.MIN_OPEN_RATE_LIMIT_MS);
        client.set(RSIntegrationConfig.SIDE_PANEL_NAVIGATION_TIMEOUT_MS.getPath(),
                GuiTimingConfig.MAX_NAVIGATION_TIMEOUT_MS);
        load(server, client);

        assertEquals(new GuiTimingConfig(
                GuiTimingConfig.MIN_OPEN_RATE_LIMIT_MS,
                GuiTimingConfig.MAX_NAVIGATION_TIMEOUT_MS), GuiTimingConfig.load());
    }

    @Test
    void forgeSpecsClampValuesOutsideBounds() {
        CommentedConfig server = defaultServerConfig();
        CommentedConfig client = defaultClientConfig();
        server.set(RSIntegrationConfig.GUI_OPEN_RATE_LIMIT_MS.getPath(),
                GuiTimingConfig.MIN_OPEN_RATE_LIMIT_MS - 1);
        client.set(RSIntegrationConfig.SIDE_PANEL_NAVIGATION_TIMEOUT_MS.getPath(),
                GuiTimingConfig.MAX_NAVIGATION_TIMEOUT_MS + 1);

        assertTrue(RSIntegrationConfig.SERVER_SPEC.correct(server) != 0);
        assertTrue(RSIntegrationConfig.CLIENT_SPEC.correct(client) != 0);
        load(server, client);
        assertEquals(new GuiTimingConfig(
                GuiTimingConfig.MIN_OPEN_RATE_LIMIT_MS,
                GuiTimingConfig.MAX_NAVIGATION_TIMEOUT_MS), GuiTimingConfig.load());
    }

    @Test
    void valueObjectRejectsValuesOutsideBounds() {
        assertThrows(IllegalArgumentException.class, () -> new GuiTimingConfig(
                GuiTimingConfig.MIN_OPEN_RATE_LIMIT_MS - 1,
                GuiTimingConfig.DEFAULT_NAVIGATION_TIMEOUT_MS));
        assertThrows(IllegalArgumentException.class, () -> new GuiTimingConfig(
                GuiTimingConfig.DEFAULT_OPEN_RATE_LIMIT_MS,
                GuiTimingConfig.MAX_NAVIGATION_TIMEOUT_MS + 1));
    }

    @AfterEach
    void restoreDefaults() {
        load(defaultServerConfig(), defaultClientConfig());
    }

    private static CommentedConfig defaultServerConfig() {
        CommentedConfig config = CommentedConfig.inMemory();
        RSIntegrationConfig.SERVER_SPEC.correct(config);
        return config;
    }

    private static CommentedConfig defaultClientConfig() {
        CommentedConfig config = CommentedConfig.inMemory();
        RSIntegrationConfig.CLIENT_SPEC.correct(config);
        return config;
    }

    private static void load(CommentedConfig server, CommentedConfig client) {
        RSIntegrationConfig.SERVER_SPEC.setConfig(server);
        RSIntegrationConfig.CLIENT_SPEC.setConfig(client);
    }
}
