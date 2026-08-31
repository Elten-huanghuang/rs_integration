package com.huanghuang.rsintegration.mixin.plugin;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;

class EasyVillagersMixinPluginTest {
    @Test
    void skipsTradeLockMixinWhenEasyVillagersIsAbsent() {
        RSIntegrationMixinPlugin plugin = new RSIntegrationMixinPlugin();

        assertFalse(plugin.shouldApplyMixin(
                "de.maxhenkel.easyvillagers.events.GuiEvents",
                "com.huanghuang.rsintegration.mixin.easyvillagers.EasyVillagersTradeLockMixin"));
    }
}
