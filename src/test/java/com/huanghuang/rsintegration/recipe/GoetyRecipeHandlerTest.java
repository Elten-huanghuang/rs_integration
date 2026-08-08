package com.huanghuang.rsintegration.recipe;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GoetyRecipeHandlerTest {

    @Test
    void ritualEligibilityIsNeverCachedByRecipeClass() {
        assertFalse(GoetyRecipeHandler.ritual().cacheByRecipeClass());
        assertTrue(GoetyRecipeHandler.brazier().cacheByRecipeClass());
    }

    @Test
    void goetySemanticInputsTakePriorityOverGenericExtraction() {
        assertTrue(GoetyRecipeHandler.ritual().preferHandlerIngredients());
        assertTrue(GoetyRecipeHandler.brazier().preferHandlerIngredients());
    }
}
