package com.huanghuang.rsintegration.mods.slashblade;

import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class SlashBladeRecipeJsonCompatTest {
    @Test
    void restoresOnlyTheKnownEmptyRodaiOverride() {
        JsonObject repaired = SlashBladeRecipeJsonCompat.repair(
                new ResourceLocation("slashblade", "rodai_netherite_smithing"),
                new JsonObject()).getAsJsonObject();

        assertEquals("slashblade:slashblade_smithing", repaired.get("type").getAsString());
        assertEquals("slashblade:rodai_netherite", repaired.get("blade").getAsString());
        assertEquals("slashblade:rodai_diamond", repaired.getAsJsonObject("base")
                .getAsJsonObject("request").get("name").getAsString());
    }

    @Test
    void leavesOtherIdsAndNonEmptyOverridesUntouched() {
        JsonObject otherId = new JsonObject();
        assertSame(otherId, SlashBladeRecipeJsonCompat.repair(
                new ResourceLocation("slashblade", "rodai_netherite"), otherId));

        JsonObject nonEmpty = new JsonObject();
        nonEmpty.addProperty("type", "custom:test");
        assertSame(nonEmpty, SlashBladeRecipeJsonCompat.repair(
                new ResourceLocation("slashblade", "rodai_netherite_smithing"), nonEmpty));

        JsonObject obsoleteType = new JsonObject();
        obsoleteType.addProperty("type", "slashblade:slashblade_recipe");
        assertSame(obsoleteType, SlashBladeRecipeJsonCompat.repair(
                new ResourceLocation("shizuku", "mosmicblade"), obsoleteType));
    }
}
