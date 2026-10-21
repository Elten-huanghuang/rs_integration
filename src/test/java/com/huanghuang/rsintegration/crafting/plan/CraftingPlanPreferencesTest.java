package com.huanghuang.rsintegration.crafting.plan;

import com.huanghuang.rsintegration.crafting.OutputDestination;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CraftingPlanPreferencesTest {

    @TempDir
    Path directory;

    @Test
    void missingPreferenceDefaultsToRsNetwork() {
        assertEquals(OutputDestination.RS_NETWORK,
                CraftingPlanPreferences.loadOutputDestination(
                        directory.resolve("missing.json")));
    }

    @Test
    void malformedOrUnknownPreferenceDefaultsToRsNetwork() throws Exception {
        Path path = directory.resolve("crafting_plan.json");
        Files.writeString(path, "{not-json", StandardCharsets.UTF_8);
        assertEquals(OutputDestination.RS_NETWORK,
                CraftingPlanPreferences.loadOutputDestination(path));

        Files.writeString(path, "{\"outputDestination\":\"UNKNOWN\"}",
                StandardCharsets.UTF_8);
        assertEquals(OutputDestination.RS_NETWORK,
                CraftingPlanPreferences.loadOutputDestination(path));
    }

    @Test
    void preservesRecipeChoicesAndExclusionsWhenSavingOutputDestination() {
        Path file = directory.resolve("crafting_plan.json");
        assertTrue(CraftingPlanPreferences.save(file,
                new CraftingPlanPreferences.Snapshot(OutputDestination.RS_NETWORK,
                        Map.of("minecraft:oak_planks", "minecraft:oak_planks"),
                        Set.of("minecraft:oak_planks"))));

        assertTrue(CraftingPlanPreferences.saveOutputDestination(
                file, OutputDestination.PLAYER_INVENTORY));
        CraftingPlanPreferences.Snapshot loaded = CraftingPlanPreferences.load(file);

        assertEquals(OutputDestination.PLAYER_INVENTORY, loaded.outputDestination());
        assertEquals("minecraft:oak_planks",
                loaded.recipeChoices().get("minecraft:oak_planks"));
        assertTrue(loaded.excludedRecipes().contains("minecraft:oak_planks"));
    }

    @Test
    void canSetAndClearAResourceRecipeChoice() {
        Path file = directory.resolve("crafting_plan.json");
        String key = "rs_integration:stack/test";

        assertTrue(CraftingPlanPreferences.saveRecipeChoice(
                file, key, CraftingPlanPreferences.NO_RECIPE));
        assertEquals(CraftingPlanPreferences.NO_RECIPE,
                CraftingPlanPreferences.load(file).recipeChoices().get(key));

        assertTrue(CraftingPlanPreferences.saveRecipeChoice(file, key, null));
        assertFalse(CraftingPlanPreferences.load(file).recipeChoices().containsKey(key));
    }

    @Test
    void oldFileWithOnlyOutputDestinationLoadsWithEmptyMaps() throws Exception {
        Path file = directory.resolve("crafting_plan.json");
        Files.writeString(file, """
                {
                  "outputDestination": "PLAYER_INVENTORY"
                }
                """);

        CraftingPlanPreferences.Snapshot loaded = CraftingPlanPreferences.load(file);

        assertEquals(OutputDestination.PLAYER_INVENTORY, loaded.outputDestination());
        assertTrue(loaded.recipeChoices().isEmpty());
        assertTrue(loaded.excludedRecipes().isEmpty());
    }
}
