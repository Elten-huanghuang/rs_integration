package com.huanghuang.rsintegration.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PassiveTickItemsConfigTest {
    private static final String TOTEM_OF_BLESSING = "composite_material:primitive_totem";

    @Test
    void defaultIncludesTotemOfBlessingAsReadOnlyTickItem() {
        assertTrue(RSIntegrationConfig.DEFAULT_PASSIVE_TICK_ITEMS.contains(TOTEM_OF_BLESSING));
        assertTrue(RSIntegrationConfig.DEFAULT_PASSIVE_TICK_ITEMS.stream()
                .noneMatch(entry -> entry.startsWith(TOTEM_OF_BLESSING + "|")));
    }

    @Test
    void legacyDefaultGainsTotemOfBlessing() {
        List<String> legacyDefault = List.of(
                "reliquary:pyromancer_staff|mutates",
                "enigmaticaddons:artificial_flower|mutates",
                "forbidden_arcanus:spectral_eye_amulet|mutates",
                "apotheosis:potion_charm|mutates",
                "muyimeng_charm:fused_potion_charm|mutates");

        assertEquals(RSIntegrationConfig.DEFAULT_PASSIVE_TICK_ITEMS,
                RSIntegrationConfig.migratePassiveTickItems(legacyDefault));
    }

    @Test
    void customizedPassiveTickItemsArePreserved() {
        List<String> custom = List.of("example:custom_charm");
        assertSame(custom, RSIntegrationConfig.migratePassiveTickItems(custom));

        List<String> current = RSIntegrationConfig.DEFAULT_PASSIVE_TICK_ITEMS;
        assertSame(current, RSIntegrationConfig.migratePassiveTickItems(current));
    }
}
