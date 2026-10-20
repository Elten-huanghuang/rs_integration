package com.huanghuang.rsintegration.mods.forbidden;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FaRitualStarterFallbackContractTest {
    @Test
    void starterLookupFallsBackFromStorageToPlayerInventory() throws IOException {
        String helper = Files.readString(Path.of(
                "src/main/java/com/huanghuang/rsintegration/mods/forbidden/FaRitualHelper.java"));
        String lookup = helper.substring(helper.indexOf("static StarterResult findRitualStarterItem"),
                helper.indexOf("static boolean canStartRitual"));

        assertTrue(lookup.contains("player.getInventory().items"));
        assertTrue(lookup.contains("player.getInventory().offhand"));
        assertTrue(lookup.contains("player.getInventory().armor"));
        assertTrue(helper.contains("Failed to evaluate RitualStarterItem"));
        assertTrue(lookup.contains("ItemStack extractionTemplate = candidate.copyWithCount(1)"));
        assertFalse(lookup.contains("if (endpoint == null || FAReflection.ritualStarterItemClass == null)"));

        String validation = helper.substring(helper.indexOf("static boolean canStartRitual"),
                helper.indexOf("private static StarterResult findInInventory"));
        assertTrue(validation.contains("ItemStack probe = stack.copy()"));
        assertTrue(validation.contains(".invoke(probe.getItem(), probe)"));
    }

    @Test
    void inventoryStarterIsConsumedAfterSuccessfulStart() throws IOException {
        String delegate = Files.readString(Path.of(
                "src/main/java/com/huanghuang/rsintegration/mods/forbidden/FaBatchDelegate.java"));
        int method = delegate.indexOf("private void consumeRitualStarterUse");
        String body = delegate.substring(method, delegate.indexOf("\n    }", method) + 6);

        assertTrue(body.contains("FaRitualHelper.consumeRitualStarterUse"));
        assertTrue(body.contains("starterFromEndpoint != null ? storageEndpoint() : null"));
    }
}
