package com.huanghuang.rsintegration.crafting.plan;

import com.huanghuang.rsintegration.crafting.OutputDestination;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
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

class CraftingPlanPreferencesTest extends BootstrapTest {

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
                        Map.of("minecraft:oak_planks",
                                new RecipeChoice.Normal(new ResourceLocation(
                                        "minecraft", "oak_planks"))),
                        Map.of(),
                        Set.of("minecraft:oak_planks"))));

        assertTrue(CraftingPlanPreferences.saveOutputDestination(
                file, OutputDestination.PLAYER_INVENTORY));
        CraftingPlanPreferences.Snapshot loaded = CraftingPlanPreferences.load(file);

        assertEquals(OutputDestination.PLAYER_INVENTORY, loaded.outputDestination());
        assertEquals(new RecipeChoice.Normal(new ResourceLocation(
                        "minecraft", "oak_planks")),
                loaded.recipeChoices().get("minecraft:oak_planks"));
        assertTrue(loaded.excludedRecipes().contains("minecraft:oak_planks"));
    }

    @Test
    void canSetAndClearAResourceRecipeChoice() {
        Path file = directory.resolve("crafting_plan.json");
        String key = "rs_integration:stack/test";

        assertTrue(CraftingPlanPreferences.saveRecipeChoice(
                file, key, RecipeChoice.NoRecipe.INSTANCE));
        assertEquals(RecipeChoice.NoRecipe.INSTANCE,
                CraftingPlanPreferences.load(file).recipeChoices().get(key));

        assertTrue(CraftingPlanPreferences.saveRecipeChoice(file, key, (RecipeChoice) null));
        assertFalse(CraftingPlanPreferences.load(file).recipeChoices().containsKey(key));
    }

    @Test
    void ingredientChoicePreservesNbtAndCanBeCleared() {
        Path file = directory.resolve("crafting_plan.json");
        String key = "minecraft:oak_planks#slot";
        ItemStack chosen = new ItemStack(Items.OAK_LOG);
        chosen.getOrCreateTag().putString("variant", "fine");

        assertTrue(CraftingPlanPreferences.saveIngredientChoice(file, key, chosen));
        ItemStack loaded = CraftingPlanPreferences.load(file)
                .ingredientChoices().get(key);
        assertTrue(ItemStack.isSameItemSameTags(chosen, loaded));

        assertTrue(CraftingPlanPreferences.saveIngredientChoice(file, key, null));
        assertFalse(CraftingPlanPreferences.load(file).ingredientChoices().containsKey(key));
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
