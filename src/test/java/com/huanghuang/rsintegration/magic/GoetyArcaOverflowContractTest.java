package com.huanghuang.rsintegration.magic;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class GoetyArcaOverflowContractTest {
    private static final Path GOETY_MODULE = Path.of(
            "src", "main", "java", "com", "huanghuang", "rsintegration",
            "mods", "goety", "GoetyRSModule.java");
    private static final Path MIXIN_CONFIG = Path.of(
            "src", "main", "resources", "rs_integration.mixins.json");
    private static final Path OVERFLOW_MIXIN = Path.of(
            "src", "main", "java", "com", "huanghuang", "rsintegration",
            "mixin", "goety", "SEImpSoulGainMixin.java");
    private static final Path OVERFLOW_ROUTER = Path.of(
            "src", "main", "java", "com", "huanghuang", "rsintegration",
            "magic", "GoetySoulOverflow.java");
    private static final Path RS_OVERFLOW_STORAGE = Path.of(
            "src", "main", "java", "com", "huanghuang", "rsintegration",
            "magic", "GoetySoulOverflowRs.java");

    @Test
    void arcaBindingRoutesSoulOverflowToRsAndBeyondDimensions() throws Exception {
        String module = Files.readString(GOETY_MODULE);
        String mixins = Files.readString(MIXIN_CONFIG);
        String overflowMixin = Files.readString(OVERFLOW_MIXIN);
        String router = Files.readString(OVERFLOW_ROUTER);
        String rsOverflow = Files.readString(RS_OVERFLOW_STORAGE);

        assertTrue(module.contains("ARCA_TYPE_ID"));
        assertTrue(module.contains("\"goety:arca\""));
        assertTrue(mixins.contains("goety.SEHelperSoulGainMixin"));
        assertTrue(mixins.contains("goety.SEImpSoulGainMixin"));
        assertTrue(overflowMixin.contains("GoetySoulOverflow.route(player, overflow)"));
        assertTrue(rsOverflow.contains("AltarBinding.RS_NETWORK"));
        assertTrue(router.contains("AltarBinding.BD_NETWORK"));
    }
}
