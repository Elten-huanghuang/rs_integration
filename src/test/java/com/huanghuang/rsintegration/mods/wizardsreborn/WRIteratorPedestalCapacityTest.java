package com.huanghuang.rsintegration.mods.wizardsreborn;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WRIteratorPedestalCapacityTest {

    @Test
    void rejectsIteratorWithFewerPedestalsThanRecipeIngredients() {
        assertFalse(WRBatchDelegate.hasIteratorPedestalCapacity(9, 8));
    }

    @Test
    void acceptsExactOrAdditionalPedestalCapacity() {
        assertTrue(WRBatchDelegate.hasIteratorPedestalCapacity(9, 9));
        assertTrue(WRBatchDelegate.hasIteratorPedestalCapacity(9, 12));
    }

    @Test
    void rejectsRecipesWithoutUsableIngredients() {
        assertFalse(WRBatchDelegate.hasIteratorPedestalCapacity(0, 9));
    }
}
