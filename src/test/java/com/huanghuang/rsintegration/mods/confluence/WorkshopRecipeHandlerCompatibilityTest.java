package com.huanghuang.rsintegration.mods.confluence;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkshopRecipeHandlerCompatibilityTest {

    @BeforeAll
    static void registerConfluenceType() {
        ConfluenceRSModule.INSTANCE.registerModType();
    }

    @Test
    void acceptsLegacyAndTerraCurioRecipeNamespaces() {
        assertTrue(WorkshopRecipeHandler.isSupportedRecipeClassName(
                "org.confluence.mod.recipe.WorkshopRecipe"));
        assertTrue(WorkshopRecipeHandler.isSupportedRecipeClassName(
                "org.confluence.terra_curio.recipe.WorkshopRecipe"));
        assertFalse(WorkshopRecipeHandler.isSupportedRecipeClassName(
                "org.confluence.other.recipe.WorkshopRecipe"));
    }

    @Test
    void acceptsLegacyAndTerraCurioAmountIngredients() {
        assertTrue(WorkshopRecipeHandler.isAmountIngredientClassName(
                "org.confluence.mod.recipe.AmountIngredient"));
        assertTrue(WorkshopRecipeHandler.isAmountIngredientClassName(
                "org.confluence.terra_curio.recipe.AmountIngredient"));
        assertFalse(WorkshopRecipeHandler.isAmountIngredientClassName(
                "org.confluence.terra_curio.recipe.WorkshopRecipe"));
    }

    @Test
    void mapsBothWorkshopJeiIdsToTheCanonicalBindingType() {
        assertEquals("confluence", com.huanghuang.rsintegration.ModType
                .filterForJeiUid("confluence:workshop"));
        assertEquals("confluence", com.huanghuang.rsintegration.ModType
                .filterForJeiUid("terra_curio:workshop"));
    }

    @Test
    void exposesBothRuntimeModIds() {
        assertEquals(java.util.List.of("confluence", "terra_curio"),
                ConfluenceRSModule.INSTANCE.modIds());
    }
}
