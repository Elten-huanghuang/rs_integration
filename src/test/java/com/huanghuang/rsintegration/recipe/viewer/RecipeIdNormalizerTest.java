package com.huanghuang.rsintegration.recipe.viewer;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecipeIdNormalizerTest {

    @Test
    void preservesCosmopolitanTisaneVirtualId() {
        ResourceLocation id = new ResourceLocation(
                "cosmopolitan", "jei.tisane.wild_salmonberries");

        assertEquals(id, RecipeIdNormalizer.normalize(id));
        assertTrue(RecipeIdNormalizer.isCosmopolitanTisane(id));
    }

    @Test
    void stillUnwrapsPaginationIds() {
        ResourceLocation wrapped = new ResourceLocation("example", "jei.machine/3");

        assertEquals(new ResourceLocation("example", "machine"),
                RecipeIdNormalizer.normalize(wrapped));
        assertFalse(RecipeIdNormalizer.isCosmopolitanTisane(wrapped));
    }

    @Test
    void rejectsIncompleteTisanePrefix() {
        ResourceLocation incomplete = new ResourceLocation("cosmopolitan", "jei.tisane.");

        assertFalse(RecipeIdNormalizer.isCosmopolitanTisane(incomplete));
    }
}
