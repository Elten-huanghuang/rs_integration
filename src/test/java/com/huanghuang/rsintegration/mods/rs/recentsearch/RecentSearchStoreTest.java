package com.huanghuang.rsintegration.mods.rs.recentsearch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecentSearchStoreTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void roundTripsUnicodeAndFavorites() throws IOException {
        Path path = temporaryDirectory.resolve("history.json");
        List<RecentSearchEntry> entries = List.of(
                new RecentSearchEntry("#石头 shitou", true),
                new RecentSearchEntry("@create", false)
        );

        RecentSearchStore.save(path, entries);
        RecentSearchStore.LoadResult result = RecentSearchStore.load(path, 100);

        assertTrue(result.accepted());
        assertFalse(result.corrupt());
        assertEquals(entries, result.entries());
        assertFalse(Files.exists(path.resolveSibling("history.json.tmp")));
    }

    @Test
    void rejectsUnknownSchemaWithoutTreatingItAsCorruption() throws IOException {
        Path path = temporaryDirectory.resolve("history.json");
        Files.writeString(path, "{\"schema\":999,\"entries\":[]}");

        RecentSearchStore.LoadResult result = RecentSearchStore.load(path, 100);

        assertFalse(result.accepted());
        assertFalse(result.corrupt());
    }

    @Test
    void ignoresMalformedEntriesAndHonorsLoadCapacity() throws IOException {
        Path path = temporaryDirectory.resolve("history.json");
        Files.writeString(path, """
                {"schema":1,"entries":[
                  {"query":"one","favorite":false},
                  {"bad":"entry"},
                  {"query":"two","favorite":true}
                ]}
                """);

        RecentSearchStore.LoadResult result = RecentSearchStore.load(path, 1);

        assertTrue(result.accepted());
        assertEquals(List.of(new RecentSearchEntry("one", false)), result.entries());
    }

    @Test
    void damagedJsonIsReportedAsCorrupt() throws IOException {
        Path path = temporaryDirectory.resolve("history.json");
        Files.writeString(path, "not json");

        RecentSearchStore.LoadResult result = RecentSearchStore.load(path, 100);

        assertFalse(result.accepted());
        assertTrue(result.corrupt());
        assertTrue(result.entries().isEmpty());
    }
}
