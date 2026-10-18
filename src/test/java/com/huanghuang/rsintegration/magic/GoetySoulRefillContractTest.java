package com.huanghuang.rsintegration.magic;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GoetySoulRefillContractTest {
    @Test
    void refillFillsAllRemainingArcaSpace() {
        assertEquals(500, GoetySoulRefill.refillRequest(9_500, 10_000, 500));
        assertEquals(1_000, GoetySoulRefill.refillRequest(9_000, 10_000, 500));
        assertEquals(100_000, GoetySoulRefill.refillRequest(0, 100_000, 10_000));
        assertEquals(0, GoetySoulRefill.refillRequest(10_000, 10_000, 500));
    }

    @Test
    void lossHookIsEventDrivenAndThrottled() throws IOException {
        String refill = source("magic", "GoetySoulRefill.java");
        String mixin = source("mixin", "goety", "SEHelperSoulLossMixin.java");
        String config = source("config", "RSIntegrationConfig.java");

        assertTrue(mixin.contains("decreaseSESouls"));
        assertTrue(mixin.contains(
                "Player player, int souls, CallbackInfoReturnable<Boolean> callback"));
        assertTrue(refill.contains("RETRY_COOLDOWN_TICKS = 20L"));
        assertTrue(refill.contains("NEXT_ATTEMPT"));
        assertTrue(refill.contains("class Scope"));
        assertTrue(config.contains("enableGoetySoulReverseRefill"));
    }

    @Test
    void reverseRefillRestoresBindingFromConnectorNbt() throws IOException {
        String refillRs = source("magic", "GoetySoulRefillRs.java");

        assertTrue(refillRs.contains(
                "RSAltarBindingResolver.resolveNetworkForAltar(player, dim, pos)"));
        assertFalse(refillRs.contains("AltarBindingRegistry.getBinding"));
    }

    private static String source(String... parts) throws IOException {
        Path path = Path.of("src/main/java/com/huanghuang/rsintegration", parts[0]);
        for (int i = 1; i < parts.length; i++) path = path.resolve(parts[i]);
        return Files.readString(path);
    }
}
