package com.huanghuang.rsintegration.unifiedgrid.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UnifiedGridPreferencesTest {
    private static final int FILTER_LEVELS = 3;

    @TempDir
    Path directory;

    @Test
    void missingFileDefaultsToAll() {
        assertEquals(0, UnifiedGridPreferences.loadFilter(
                directory.resolve("missing.json"), FILTER_LEVELS));
    }

    @Test
    void savedFilterIsRestored() {
        Path path = directory.resolve("unified_grid.json");

        assertTrue(UnifiedGridPreferences.saveFilter(path, 2, FILTER_LEVELS));
        assertEquals(2, UnifiedGridPreferences.loadFilter(path, FILTER_LEVELS));
    }

    @Test
    void malformedOrOutOfRangeFilterDefaultsToAll() throws Exception {
        Path path = directory.resolve("unified_grid.json");
        Files.writeString(path, "{not-json", StandardCharsets.UTF_8);
        assertEquals(0, UnifiedGridPreferences.loadFilter(path, FILTER_LEVELS));

        assertTrue(UnifiedGridPreferences.saveFilter(path, 1, FILTER_LEVELS));
        Files.writeString(path, """
                {
                  "version": 1,
                  "filter": 99
                }
                """, StandardCharsets.UTF_8);
        assertEquals(0, UnifiedGridPreferences.loadFilter(path, FILTER_LEVELS));
    }

    @Test
    void invalidFilterIsNotSaved() {
        assertFalse(UnifiedGridPreferences.saveFilter(
                directory.resolve("unified_grid.json"), 3, FILTER_LEVELS));
    }
}
