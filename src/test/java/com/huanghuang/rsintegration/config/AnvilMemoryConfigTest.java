package com.huanghuang.rsintegration.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class AnvilMemoryConfigTest {
    @Test
    void legacyDefaultGainsNewAdapters() {
        assertEquals(RSIntegrationConfig.DEFAULT_ANVIL_MEMORY_ADAPTERS,
                RSIntegrationConfig.migrateAnvilMemoryAdapters(2, List.of("minecraft_anvil")));
    }

    @Test
    void customAndCurrentValuesArePreserved() {
        List<String> custom = List.of("minecraft_anvil", "custom_anvil");
        assertSame(custom, RSIntegrationConfig.migrateAnvilMemoryAdapters(2, custom));

        List<String> current = List.of("minecraft_anvil");
        assertSame(current, RSIntegrationConfig.migrateAnvilMemoryAdapters(3, current));
    }
}
