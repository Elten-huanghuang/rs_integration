package com.huanghuang.rsintegration.mods.goety;

import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GoetyRecipeHandlerTest {

    private static final class CraftItemRitual {}
    private static final class EnchantItemRitual {}
    public static final class NoSacrificeRecipe {
        public boolean requiresSacrifice() { return false; }
    }
    private static final class CustomRitual {}
    private static final class TeleportRitual {}

    private record StubRecipe(ResourceLocation id) implements Recipe<Container> {
        @Override public boolean matches(Container container, Level level) { return false; }
        @Override public ItemStack assemble(Container container, RegistryAccess access) {
            return ItemStack.EMPTY;
        }
        @Override public boolean canCraftInDimensions(int width, int height) { return false; }
        @Override public ItemStack getResultItem(RegistryAccess access) {
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
    void jeiPlaceholdersRequireManualStartEvenForCustomRitualClasses() {
        var recipe = new NoSacrificeRecipe();
        var ritual = new CustomRitual();
        assertEquals(GoetyRitualPolicy.Execution.MANUAL_CONFIRMATION,
                GoetyRitualPolicy.classify(recipe, ritual, new ResourceLocation("goety", "jei_dummy/none")));
        assertEquals(GoetyRitualPolicy.Execution.MANUAL_CONFIRMATION,
                GoetyRitualPolicy.classify(recipe, ritual, new ResourceLocation("goety", "jei_dummy/sacrifice")));
        assertEquals(GoetyRitualPolicy.Execution.AUTOMATIC,
                GoetyRitualPolicy.classify(recipe, ritual, new ResourceLocation("goety", "frost_staff")));
        assertEquals(GoetyRitualPolicy.Execution.UNSUPPORTED,
                GoetyRitualPolicy.classify(recipe, new TeleportRitual(),
                        new ResourceLocation("goety", "jei_dummy/none")));
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
