package com.huanghuang.rsintegration.compat.jei;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JeiMachineCategoryPolicyTest {

    private static final String COOKING_POT_RECIPE =
            "vectorwing.farmersdelight.common.crafting.CookingPotRecipe";

    @Test
    void farmersDelightCategoryMayUseCookingPotClassFallback() {
        assertTrue(JeiMachineCategoryPolicy.allowClassFallback(
                new ResourceLocation("farmersdelight", "cooking"),
                COOKING_POT_RECIPE, null));
    }

    @Test
    void minersDelightCategoryCannotBorrowFarmersDelightBinding() {
        assertFalse(JeiMachineCategoryPolicy.allowClassFallback(
                new ResourceLocation("miners_delight", "cooking"),
                COOKING_POT_RECIPE, null));
    }

    @Test
    void explicitMachineCategoryMappingTakesPriority() {
        assertTrue(JeiMachineCategoryPolicy.allowClassFallback(
                new ResourceLocation("example", "cooking"),
                COOKING_POT_RECIPE, "example_cooking_pot"));
    }

    @Test
    void unrelatedRecipeClassesKeepExistingFallbacks() {
        assertTrue(JeiMachineCategoryPolicy.allowClassFallback(
                new ResourceLocation("example", "machine"),
                "example.recipe.CustomRecipe", null));
    }
}
