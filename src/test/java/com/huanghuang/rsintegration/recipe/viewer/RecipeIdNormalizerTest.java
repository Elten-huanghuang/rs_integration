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

    @Test
    void detectsSyntheticEnchantmentInfoDisplayId() {
        ResourceLocation displayId = new ResourceLocation(
                "minecraft", "enchantment.tacz.modern_kinetic_gun.emxarms.emx_sf7b");

        assertTrue(RecipeIdNormalizer.isEnchantmentInfoDisplayId(displayId));
    }

    @Test
    void preservesRealEnchantedBookRecipeIds() {
        ResourceLocation craftTweakerRecipe = new ResourceLocation(
                "crafttweaker", "apotheosis.enchanted_book.life_mending");
        ResourceLocation apotheosisRecipe = new ResourceLocation(
                "apotheosis", "enchanting/stalwart_dungeons/tungsten_hammer");
        ResourceLocation otherNamespace = new ResourceLocation(
                "example", "enchantment.test");

        assertFalse(RecipeIdNormalizer.isEnchantmentInfoDisplayId(craftTweakerRecipe));
        assertFalse(RecipeIdNormalizer.isEnchantmentInfoDisplayId(apotheosisRecipe));
        assertFalse(RecipeIdNormalizer.isEnchantmentInfoDisplayId(otherNamespace));
    }
}
