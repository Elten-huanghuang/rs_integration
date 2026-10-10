package com.huanghuang.rsintegration.mixin.plugin;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class UntilEternityMixinPluginTest {
    private static final String MIXIN =
            "com.huanghuang.rsintegration.mixin.untileternity.VibrantAmethystBlessingEventsMixin";

    @Test
    void nativeBlessingMixinRequiresTheMatchingEventHandler() {
        RSIntegrationMixinPlugin plugin = new RSIntegrationMixinPlugin();
        assertFalse(plugin.shouldApplyMixin("com.carrot123.until_eternity.event.Absent", MIXIN));

        boolean handlerPresent = getClass().getClassLoader().getResource(
                "com/carrot123/until_eternity/event/VibrantAmethystBlessingEvents.class") != null;
        assertEquals(handlerPresent && RSIntegrationMixinPlugin.hasResonanceBackend(),
                plugin.shouldApplyMixin(
                        "com.carrot123.until_eternity.event.VibrantAmethystBlessingEvents", MIXIN));
    }
}
