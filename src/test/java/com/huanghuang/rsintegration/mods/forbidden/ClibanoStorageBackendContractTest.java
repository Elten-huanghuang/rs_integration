package com.huanghuang.rsintegration.mods.forbidden;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClibanoStorageBackendContractTest {

    @Test
    void preparationKeepsSelectedStorageAndOutputEvacuationUsesIt() throws IOException {
        String source = Files.readString(Path.of("src/main/java/com/huanghuang/rsintegration/mods/forbidden/ClibanoBatchDelegate.java"));

        assertTrue(source.contains("CraftStorageEndpoint selectedStorage = storageEndpoint();"));
        assertTrue(source.contains("setStorageEndpoint(selectedStorage);"));
        assertTrue(source.contains("ItemStack remainder = insertIntoStorage(player, extracted, false);"));
        assertFalse(source.contains("TrackedNetworkInsertion.insert(network, player, extracted)"));
    }
}
