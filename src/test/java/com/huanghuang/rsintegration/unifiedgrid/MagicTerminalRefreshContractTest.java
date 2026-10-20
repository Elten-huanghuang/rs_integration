package com.huanghuang.rsintegration.unifiedgrid;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class MagicTerminalRefreshContractTest {
    private static final Path SESSION_SOURCE = Path.of(
            "src", "main", "java", "com", "huanghuang", "rsintegration",
            "unifiedgrid", "UnifiedGridSession.java");

    @Test
    void magicUpdatesAdvanceTheirSequenceAndRefreshImmediately() throws Exception {
        String source = Files.readString(SESSION_SOURCE);

        assertTrue(source.contains("epoch, sequence++"));
        assertTrue(source.contains("sequence = 0;"));
        assertTrue(source.contains("if (!resetRequested && sequence < Integer.MAX_VALUE - 16)"));
        assertTrue(source.contains("seenVersion = -1;"));
        assertTrue(source.contains("refreshSnapshot();"));
    }
}
