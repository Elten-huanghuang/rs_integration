package com.huanghuang.rsintegration.mods.rs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GridSearchDiskStoreTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void roundTripsUnicodeTooltipText() throws IOException {
        Path cache = temporaryDirectory.resolve("search.bin.gz");
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("item|minecraft:stone|-", "stone\n石头\nshitou\nst\n");
        entries.put("fluid|minecraft:water|-", "water\n水\nshui\ns\n");

        assertEquals(2, GridSearchDiskStore.save(cache, "zh_cn|mods", entries,
                100, 1_048_576));
        GridSearchDiskStore.LoadResult result = GridSearchDiskStore.load(
                cache, "zh_cn|mods", 100, 1_048_576);

        assertTrue(result.accepted());
        assertFalse(result.corrupt());
        assertEquals(entries, result.entries());
        assertFalse(Files.exists(cache.resolveSibling("search.bin.gz.tmp")));
    }

    @Test
    void rejectsAContextFromAnotherLanguageOrModSet() throws IOException {
        Path cache = temporaryDirectory.resolve("search.bin.gz");
        GridSearchDiskStore.save(cache, "zh_cn|mods-a", Map.of("key", "text"),
                100, 1_048_576);

        GridSearchDiskStore.LoadResult result = GridSearchDiskStore.load(
                cache, "en_us|mods-b", 100, 1_048_576);

        assertFalse(result.accepted());
        assertFalse(result.corrupt());
        assertTrue(result.entries().isEmpty());
    }

    @Test
    void ignoresDamagedFiles() throws IOException {
        Path cache = temporaryDirectory.resolve("search.bin.gz");
        Files.writeString(cache, "not a gzip cache");

        GridSearchDiskStore.LoadResult result = GridSearchDiskStore.load(
                cache, "context", 100, 1_048_576);

        assertFalse(result.accepted());
        assertTrue(result.corrupt());
        assertTrue(result.entries().isEmpty());
    }

    @Test
    void capacityKeepsNewestEntries() throws IOException {
        Path cache = temporaryDirectory.resolve("search.bin.gz");
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("old", "one");
        entries.put("middle", "two");
        entries.put("new", "three");

        assertEquals(2, GridSearchDiskStore.save(cache, "context", entries,
                2, 1_048_576));
        GridSearchDiskStore.LoadResult result = GridSearchDiskStore.load(
                cache, "context", 2, 1_048_576);

        assertEquals(Map.of("middle", "two", "new", "three"), result.entries());
    }
}
