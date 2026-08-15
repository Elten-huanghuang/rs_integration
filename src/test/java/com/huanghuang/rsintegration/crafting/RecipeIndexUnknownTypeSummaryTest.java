package com.huanghuang.rsintegration.crafting;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RecipeIndexUnknownTypeSummaryTest {

    @Test
    void ordersTypesByCountAndIncludesSamples() {
        Map<String, RecipeIndex.UnknownRecipeTypeStats> types = new HashMap<>();
        record(types, "example:small", "example:one");
        record(types, "example:large", "example:first");
        record(types, "example:large", "example:second");

        String summary = RecipeIndex.summarizeUnknownRecipeTypes(types);

        assertTrue(summary.indexOf("example:large=2") < summary.indexOf("example:small=1"));
        assertTrue(summary.contains("example:first"));
        assertTrue(summary.contains("example:second"));
    }

    private static void record(Map<String, RecipeIndex.UnknownRecipeTypeStats> types,
                               String type, String recipe) {
        types.computeIfAbsent(type, ignored -> new RecipeIndex.UnknownRecipeTypeStats())
                .record(new ResourceLocation(recipe));
    }
}
