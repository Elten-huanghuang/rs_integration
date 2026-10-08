package com.huanghuang.rsintegration.update;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ModrinthUpdateCheckerTest {
    @Test
    void selectsHighestNumericVersionFromUnsortedResponse() {
        var update = find(version("1.5.0.9"), version("1.5.0.10"), version("1.5.0.5"));
        assertEquals("1.5.0.10", update.version());
        assertEquals("https://modrinth.com/mod/rs-integration/version/Ab123", update.downloadUrl());
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.5.0.3", "1.5.0.4", "1.5.0.4-beta"})
    void doesNotOfferOlderOrEqualVersions(String number) {
        assertNull(find(version(number)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"release", "beta"})
    void acceptsPublishedReleaseAndBeta(String type) {
        JsonObject entry = version("1.5.0.5");
        entry.addProperty("version_type", type);
        assertEquals("1.5.0.5", find(entry).version());
    }

    @ParameterizedTest
    @ValueSource(strings = {"alpha", "unlisted", "draft", "fabric", "minecraft", "badId", "badNumber"})
    void filtersIncompatibleOrUnavailableVersions(String reason) {
        JsonObject entry = version("1.5.0.5");
        switch (reason) {
            case "alpha" -> entry.addProperty("version_type", "alpha");
            case "unlisted", "draft" -> entry.addProperty("status", reason);
            case "fabric" -> entry.add("loaders", values("fabric"));
            case "minecraft" -> entry.add("game_versions", values("1.21.1"));
            case "badId" -> entry.addProperty("id", "../elsewhere");
            case "badNumber" -> entry.addProperty("version_number", "invalid");
        }
        assertNull(find(entry));
    }

    @Test
    void skipsIncompleteEntriesAndAllowsOlderApiWithoutStatus() {
        JsonObject incomplete = version("1.5.0.99");
        incomplete.remove("version_number");
        JsonObject valid = version("1.5.0.5");
        valid.remove("status");
        assertEquals("1.5.0.5", find(new JsonObject(), incomplete, valid).version());
    }

    @Test
    void handlesNoVersions() {
        assertNull(find());
    }

    @Test
    void queriesActualProjectWithForgeAndMinecraftFilters() {
        assertEquals("https://api.modrinth.com/v2/project/vtvveeqA/version"
                        + "?loaders=%5B%22forge%22%5D&game_versions=%5B%221.20.1%22%5D",
                ModrinthUpdateChecker.versionsUri("1.20.1").toASCIIString());
        assertThrows(IllegalArgumentException.class,
                () -> ModrinthUpdateChecker.versionsUri("1.20.1&other=value"));
    }

    private static ModrinthUpdateChecker.Update find(JsonObject... entries) {
        JsonArray versions = new JsonArray();
        for (JsonObject entry : entries) versions.add(entry);
        return ModrinthUpdateChecker.findUpdate(versions.toString(), "1.5.0.4", "1.20.1");
    }

    private static JsonObject version(String number) {
        JsonObject entry = new JsonObject();
        entry.addProperty("id", "Ab123");
        entry.addProperty("version_number", number);
        entry.addProperty("version_type", "beta");
        entry.addProperty("status", "listed");
        entry.add("loaders", values("forge"));
        entry.add("game_versions", values("1.20.1"));
        return entry;
    }

    private static JsonArray values(String value) {
        JsonArray values = new JsonArray();
        values.add(value);
        return values;
    }
}
