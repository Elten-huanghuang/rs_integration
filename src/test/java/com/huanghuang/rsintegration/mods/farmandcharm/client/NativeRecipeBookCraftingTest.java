package com.huanghuang.rsintegration.mods.farmandcharm.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeRecipeBookCraftingTest {

    @Test
    void acceptsCookingPotAndRoasterRecipeClasses() {
        assertTrue(NativeRecipeBookCrafting.isSupportedRecipeClass(
                "net.satisfy.farm_and_charm.recipe.CookingPotRecipe"));
        assertTrue(NativeRecipeBookCrafting.isSupportedRecipeClass(
                "net.satisfy.farm_and_charm.recipe.RoasterRecipe"));
        assertFalse(NativeRecipeBookCrafting.isSupportedRecipeClass(
                "net.satisfy.farm_and_charm.recipe.StoveRecipe"));
    }
}
