package com.huanghuang.rsintegration.mods.arsnouveau;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArsRecipeClassifierTest {
    @Test
    void nbtTransformingApparatusRecipesAreSupported() {
        assertTrue(ArsRecipeClassifier.isDynamicApparatus("ars_nouveau:enchantment"));
        assertTrue(ArsRecipeClassifier.isDynamicApparatus("ars_nouveau:armor_upgrade"));
        assertTrue(ArsRecipeClassifier.isApparatus("ars_nouveau:enchantment"));
        assertTrue(ArsRecipeClassifier.isApparatus("ars_nouveau:armor_upgrade"));
        assertFalse(ArsRecipeClassifier.isDynamicApparatus("ars_nouveau:enchanting_apparatus"));
        assertFalse(ArsRecipeClassifier.isDynamicApparatus("ars_nouveau:imbuement"));
    }

    @Test
    void unrelatedNbtRecipesRemainExcluded() {
        assertFalse(ArsRecipeClassifier.isAutomatable("ars_nouveau:spell_write"));
        assertFalse(ArsRecipeClassifier.isAutomatable("ars_nouveau:reactive_enchantment"));
        assertFalse(ArsRecipeClassifier.isAutomatable(null));
    }
}
