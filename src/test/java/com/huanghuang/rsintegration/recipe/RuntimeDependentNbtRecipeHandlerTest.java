package com.huanghuang.rsintegration.recipe;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.core.NonNullList;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeDependentNbtRecipeHandlerTest extends BootstrapTest {

    @Test
    void malumRecognizesOnlyRecipesThatCopyNbtFromInput() {
        MalumRecipeHandler handler = new MalumRecipeHandler();

        assertTrue(handler.hasRuntimeDependentPrimaryNbt(new MalumLikeRecipe(true)));
        assertFalse(handler.hasRuntimeDependentPrimaryNbt(new MalumLikeRecipe(false)));
    }

    @Test
    void maidAltarRecognizesOnlyNonEmptyCopyInputs() {
        TlmAltarRecipeHandler handler = new TlmAltarRecipeHandler();

        assertTrue(handler.hasRuntimeDependentPrimaryNbt(
                new TlmLikeRecipe(Ingredient.of(Items.IRON_SWORD))));
        assertFalse(handler.hasRuntimeDependentPrimaryNbt(
                new TlmLikeRecipe(Ingredient.EMPTY)));
    }

    private static class BaseRecipe extends ShapelessRecipe {
        BaseRecipe(String path) {
            super(new ResourceLocation("test", path), "", CraftingBookCategory.MISC,
                    new ItemStack(Items.DIAMOND),
                    NonNullList.of(Ingredient.EMPTY, Ingredient.of(Items.IRON_INGOT)));
        }
    }

    private static final class MalumLikeRecipe extends BaseRecipe {
        @SuppressWarnings("unused")
        private final boolean useNbtFromInput;

        MalumLikeRecipe(boolean useNbtFromInput) {
            super("malum_like");
            this.useNbtFromInput = useNbtFromInput;
        }
    }

    private static final class TlmLikeRecipe extends BaseRecipe {
        @SuppressWarnings("unused")
        private final Ingredient copyInput;

        TlmLikeRecipe(Ingredient copyInput) {
            super("tlm_like");
            this.copyInput = copyInput;
        }
    }
}
