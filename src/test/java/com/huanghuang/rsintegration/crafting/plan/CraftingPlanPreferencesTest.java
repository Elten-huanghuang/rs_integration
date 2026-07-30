package com.huanghuang.rsintegration.crafting.plan;

import com.huanghuang.rsintegration.crafting.OutputDestination;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CraftingPlanPreferencesTest {

    @TempDir
    Path tempDir;

    @Test
    void missingPreferenceDefaultsToRsNetwork() {
        Path path = tempDir.resolve("missing.json");

        assertEquals(OutputDestination.RS_NETWORK,
                CraftingPlanPreferences.loadOutputDestination(path));
    }

    @Test
    void selectedDestinationSurvivesSaveAndReload() {
        Path path = tempDir.resolve("nested").resolve("crafting_plan.json");

        assertTrue(CraftingPlanPreferences.saveOutputDestination(
                path, OutputDestination.PLAYER_INVENTORY));
        assertEquals(OutputDestination.PLAYER_INVENTORY,
                CraftingPlanPreferences.loadOutputDestination(path));

        assertTrue(CraftingPlanPreferences.saveOutputDestination(
                path, OutputDestination.RS_NETWORK));
        assertEquals(OutputDestination.RS_NETWORK,
                CraftingPlanPreferences.loadOutputDestination(path));
    }

    @Test
    void malformedOrUnknownPreferenceDefaultsToRsNetwork() throws Exception {
        Path path = tempDir.resolve("crafting_plan.json");
        Files.writeString(path, "{not-json", StandardCharsets.UTF_8);
        assertEquals(OutputDestination.RS_NETWORK,
                CraftingPlanPreferences.loadOutputDestination(path));

        Files.writeString(path, "{\"outputDestination\":\"UNKNOWN\"}", StandardCharsets.UTF_8);
        assertEquals(OutputDestination.RS_NETWORK,
                CraftingPlanPreferences.loadOutputDestination(path));
    }
}
