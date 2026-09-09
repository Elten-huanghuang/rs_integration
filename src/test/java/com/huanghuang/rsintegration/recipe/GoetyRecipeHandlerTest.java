package com.huanghuang.rsintegration.recipe;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GoetyRecipeHandlerTest {

    private static final class CraftItemRitual {}
    private static final class EnchantItemRitual {}

    private record StubRecipe(ResourceLocation id) implements Recipe<Container> {
        @Override public boolean matches(Container container, Level level) { return false; }
        @Override public ItemStack assemble(Container container, net.minecraft.core.RegistryAccess access) {
            return ItemStack.EMPTY;
        }
        @Override public boolean canCraftInDimensions(int width, int height) { return false; }
        @Override public ItemStack getResultItem(net.minecraft.core.RegistryAccess access) {
            return ItemStack.EMPTY;
        }
        @Override public ResourceLocation getId() { return id; }
        @Override public RecipeSerializer<?> getSerializer() { return RecipeSerializer.SHAPELESS_RECIPE; }
        @Override public RecipeType<?> getType() { return RecipeType.CRAFTING; }
    }

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

    @Test
    void craftItemRitualOutputNbtIsRuntimeDependent() {
        assertTrue(GoetyRecipeHandler.isCraftItemRitual(new CraftItemRitual()));
        assertFalse(GoetyRecipeHandler.isCraftItemRitual(new EnchantItemRitual()));
        assertFalse(GoetyRecipeHandler.isCraftItemRitual(null));
    }

    @Test
    void substitutePlaceholderRecipesAreRecognizedForIndexExclusion() {
        assertTrue(GoetyRecipeHandler.isRuntimeTransformedSubstituteRecipeId(
                new ResourceLocation("goeticlegacy", "substitute_ritual_magic")));
        assertTrue(GoetyRecipeHandler.isRuntimeTransformedSubstituteRecipeId(
                new ResourceLocation("goeticlegacy", "substitute_ritual_end")));
        assertFalse(GoetyRecipeHandler.isRuntimeTransformedSubstituteRecipeId(
                new ResourceLocation("goety", "substitute_ritual_magic")));
        assertFalse(GoetyRecipeHandler.isRuntimeTransformedSubstituteRecipeId(
                new ResourceLocation("goeticlegacy", "ordinary_ritual")));

        GoetyRecipeHandler handler = GoetyRecipeHandler.ritual();
        assertFalse(handler.indexPrimaryOutput(new StubRecipe(
                new ResourceLocation("goeticlegacy", "substitute_ritual_magic"))));
        assertTrue(handler.indexPrimaryOutput(new StubRecipe(
                new ResourceLocation("goeticlegacy", "ordinary_ritual"))));
    }
}
