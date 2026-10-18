package com.huanghuang.rsintegration.mixin.plugin;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;

class GoetySoulMixinPluginTest {
    private static final String CAGE_MIXIN =
            "com.huanghuang.rsintegration.mixin.goety.CursedCageSoulSourceMixin";
    private static final String CANDLE_MIXIN =
            "com.huanghuang.rsintegration.mixin.goety.SoulCandlestickSoulSourceMixin";
    private static final String ALTAR_MIXIN =
            "com.huanghuang.rsintegration.mixin.goety.DarkAltarSoulSourceMixin";
    private static final String VIRTUAL_CANDLE_MIXIN =
            "com.huanghuang.rsintegration.mixin.goety.NecroBrazierVirtualCandleMixin";

    @Test
    void goetySoulMixinsStayUnloadedWhenGoetyIsAbsent() {
        RSIntegrationMixinPlugin plugin = new RSIntegrationMixinPlugin();

        assertFalse(plugin.shouldApplyMixin(
                "com.Polarice3.Goety.common.blocks.entities.CursedCageBlockEntity",
                CAGE_MIXIN));
        assertFalse(plugin.shouldApplyMixin(
                "com.Polarice3.Goety.common.blocks.entities.SoulCandlestickBlockEntity",
                CANDLE_MIXIN));
        assertFalse(plugin.shouldApplyMixin(
                "com.Polarice3.Goety.common.blocks.entities.DarkAltarBlockEntity",
                ALTAR_MIXIN));
        assertFalse(plugin.shouldApplyMixin(
                "com.Polarice3.Goety.common.blocks.entities.NecroBrazierBlockEntity",
                VIRTUAL_CANDLE_MIXIN));
    }
}
