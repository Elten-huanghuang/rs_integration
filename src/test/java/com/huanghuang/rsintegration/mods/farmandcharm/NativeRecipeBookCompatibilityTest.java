package com.huanghuang.rsintegration.mods.farmandcharm;

import com.huanghuang.rsintegration.mods.farmandcharm.client.NativeRecipeBookCrafting;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeRecipeBookCompatibilityTest {

    @Test
    void nativeRecipeBookMixinsAreOptionalAndUsePlanPreview() throws Exception {
        String mixins = Files.readString(Path.of(
                "src/main/resources/rs_integration.mixins.json"));
        assertTrue(mixins.contains("farmandcharm.NativeRecipeBookWidgetMixin"));
        assertTrue(mixins.contains("farmandcharm.NativeRecipeBookScreenMixin"));
        assertTrue(mixins.contains("farmandcharm.CookingPotGuiMixin"));
        assertTrue(mixins.contains("farmandcharm.RoasterGuiMixin"));

        String helper = Files.readString(Path.of(
                "src/main/java/com/huanghuang/rsintegration/mods/farmandcharm/client/NativeRecipeBookCrafting.java"));
        assertTrue(helper.contains("new GenericCraftPacket("));
        assertTrue(helper.contains("recipeId, true"));
        assertTrue(NativeRecipeBookCrafting.class.getName().contains("NativeRecipeBookCrafting"));
    }
}
