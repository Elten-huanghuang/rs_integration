package com.huanghuang.rsintegration.recipe.viewer;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
    void rejectsSyntheticAnvilEnchantmentDisplayId() {
        ResourceLocation displayId = new ResourceLocation(
                "minecraft", "enchantment.goetyawaken.claymore");

        assertFalse(RecipeIdNormalizer.canRequestJeiRecipe(displayId));
    }

    @Test
    void rejectsMalformedIdsOutsideEnchantmentDisplay() {
        ResourceLocation invalidPath = mock(ResourceLocation.class);
        when(invalidPath.getNamespace()).thenReturn("minecraft");
        when(invalidPath.getPath()).thenReturn("self_repair.goetyawaken:claymore");
        ResourceLocation invalidNamespace = mock(ResourceLocation.class);
        when(invalidNamespace.getNamespace()).thenReturn("invalid namespace");
        when(invalidNamespace.getPath()).thenReturn("repair");

        assertFalse(RecipeIdNormalizer.canRequestJeiRecipe(invalidPath));
        assertFalse(RecipeIdNormalizer.canRequestJeiRecipe(invalidNamespace));
        assertFalse(RecipeIdNormalizer.canRequestJeiRecipe(null));
    }

    @Test
    void acceptsRealEnchantingAndRepairRecipeIds() {
        ResourceLocation craftTweakerRecipe = new ResourceLocation(
                "crafttweaker", "apotheosis.enchanted_book.life_mending");
        ResourceLocation apotheosisRecipe = new ResourceLocation(
                "apotheosis", "enchanting/stalwart_dungeons/tungsten_hammer");
        ResourceLocation otherNamespace = new ResourceLocation(
                "example", "enchantment.test");

        ResourceLocation repairDisplay = new ResourceLocation("minecraft", "self_repair.iron_sword");

        assertTrue(RecipeIdNormalizer.canRequestJeiRecipe(craftTweakerRecipe));
        assertTrue(RecipeIdNormalizer.canRequestJeiRecipe(apotheosisRecipe));
        assertTrue(RecipeIdNormalizer.canRequestJeiRecipe(otherNamespace));
        assertTrue(RecipeIdNormalizer.canRequestJeiRecipe(repairDisplay));
    }
}
