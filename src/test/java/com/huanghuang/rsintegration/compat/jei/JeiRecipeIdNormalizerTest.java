package com.huanghuang.rsintegration.compat.jei;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JeiRecipeIdNormalizerTest {

    @Test
    void preservesCosmopolitanTisaneVirtualId() {
        ResourceLocation id = new ResourceLocation(
                "cosmopolitan", "jei.tisane.wild_salmonberries");

        assertEquals(id, JeiRecipeIdNormalizer.normalize(id));
        assertTrue(JeiRecipeIdNormalizer.isCosmopolitanTisane(id));
    }

    @Test
    void stillUnwrapsPaginationIds() {
        ResourceLocation wrapped = new ResourceLocation("example", "jei.machine/3");

        assertEquals(new ResourceLocation("example", "machine"),
                JeiRecipeIdNormalizer.normalize(wrapped));
        assertFalse(JeiRecipeIdNormalizer.isCosmopolitanTisane(wrapped));
    }

    @Test
    void rejectsIncompleteTisanePrefix() {
        ResourceLocation incomplete = new ResourceLocation("cosmopolitan", "jei.tisane.");

        assertFalse(JeiRecipeIdNormalizer.isCosmopolitanTisane(incomplete));
    }
}
