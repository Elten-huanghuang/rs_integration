package com.huanghuang.rsintegration.magic;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MagicResourcePerformanceContractTest {
    @Test
    void recoveryNeverLoadsChunksAndSkipsWorkWithoutPendingRecords() throws Exception {
        String ledger = Files.readString(Path.of(
                "src", "main", "java", "com", "huanghuang", "rsintegration",
                "magic", "MagicResourceTaskLedger.java"));
        String recovery = Files.readString(Path.of(
                "src", "main", "java", "com", "huanghuang", "rsintegration",
                "magic", "MagicMachineResourceRecovery.java"));
        String mod = Files.readString(Path.of(
                "src", "main", "java", "com", "huanghuang", "rsintegration",
                "RSIntegrationMod.java"));

        assertTrue(ledger.contains("if (pendingRecovery.isEmpty()) return;"));
        assertTrue(mod.contains("(e.getServer().getTickCount() & 3) == 0"));
        assertFalse(recovery.contains("forceLoad"));
        assertFalse(recovery.contains("getChunk("));
        assertFalse(recovery.contains("getChunkNow("));
        assertTrue(recovery.contains("if (!level.isLoaded(position))"));
    }
}
