package com.huanghuang.rsintegration.recipe;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResonantMagicUpgradeRecipeTest {
    @Test
    void resonantManaPoolUsesOneOriginalPoolAndCheapRsUpgrade() throws IOException {
        JsonObject recipe = recipe("resonant_mana_pool.json");

        assertEquals("rs_integration:resonant_mana_pool",
                recipe.getAsJsonObject("result").get("item").getAsString());
        assertTrue(recipe.toString().contains("botania:mana_pool"));
        assertTrue(recipe.toString().contains("refinedstorage:quartz_enriched_iron"));
        assertTrue(recipe.toString().contains("\"modid\":\"botania\""));
    }

    @Test
    void resonantSourceTankUsesOneOriginalJarAndCheapRsUpgrade() throws IOException {
        JsonObject recipe = recipe("resonant_source_tank.json");

        assertEquals("rs_integration:resonant_source_tank",
                recipe.getAsJsonObject("result").get("item").getAsString());
        assertTrue(recipe.toString().contains("ars_nouveau:source_jar"));
        assertTrue(recipe.toString().contains("refinedstorage:quartz_enriched_iron"));
        assertTrue(recipe.toString().contains("\"modid\":\"ars_nouveau\""));
    }

    private static JsonObject recipe(String name) throws IOException {
        String json = Files.readString(Path.of(
                "src/main/resources/data/rs_integration/recipes", name));
        return JsonParser.parseString(json).getAsJsonObject();
    }
}
