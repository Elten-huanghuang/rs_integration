package com.huanghuang.rsintegration.mods.pmmo.client;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PmmoSalvageAccessTest extends BootstrapTest {
    @Test
    void missingPmmoReturnsNoDataWithoutFailingClassLoading() {
        assertTrue(PmmoSalvageAccess.recipes().isEmpty());
        assertTrue(PmmoSalvageAccess.salvageBlock().isEmpty());
    }

    @Test
    void decodesSyncedPmmoObjectDataWithoutLinkingPmmoClasses() {
        ResourceLocation input = new ResourceLocation("minecraft", "iron_sword");
        ResourceLocation output = new ResourceLocation("minecraft", "iron_ingot");
        FakeSalvageData salvage = new FakeSalvageData(
                Map.of("smithing", 0.005D),
                Map.of("smithing", 50),
                Map.of("smithing", 800L),
                4, 0.25D, 1.0D);

        var recipes = PmmoSalvageAccess.decodeItemData(Map.of(
                input, new FakeObjectData(Map.of(output, salvage))));

        assertEquals(1, recipes.size());
        PmmoSalvageRecipe recipe = recipes.get(0);
        assertEquals(input, recipe.inputId());
        assertEquals(output, recipe.outputId());
        assertEquals(4, recipe.salvageMax());
        assertEquals(0.25D, recipe.baseChance());
        assertEquals(1.0D, recipe.maxChance());
        assertEquals(Map.of("smithing", 0.005D), recipe.chancePerLevel());
        assertEquals(Map.of("smithing", 50), recipe.levelRequirements());
        assertEquals(Map.of("smithing", 800L), recipe.xpAwards());
    }

    public record FakeObjectData(Map<ResourceLocation, FakeSalvageData> salvage) {}

    public record FakeSalvageData(
            Map<String, Double> chancePerLevel,
            Map<String, Integer> levelReq,
            Map<String, Long> xpAward,
            int salvageMax,
            double baseChance,
            double maxChance) {}
}
