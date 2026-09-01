package com.huanghuang.rsintegration.recipe;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecipeResultMethodSafetyTest extends BootstrapTest {
    @BeforeEach
    void clearSafetyCache() {
        RecipeResultMethodSafety.clear();
    }

    @Test
    void detectsClientReferenceInResultMethod() {
        assertFalse(RecipeResultMethodSafety.mayInvokeOnDedicatedServer(
                ClientUnsafeRecipe.class));
    }

    @Test
    void ordinaryCraftingResultMethodRemainsCallable() {
        assertTrue(RecipeResultMethodSafety.mayInvokeOnDedicatedServer(
                ShapelessRecipe.class));
    }

    @Test
    void dedicatedServerUsesOutputFieldWithoutInvokingUnsafeMethod() {
        ClientUnsafeRecipe recipe = new ClientUnsafeRecipe();

        ItemStack output = ModRecipeHandlers.tryGetCraftingResultItem(
                recipe, RegistryAccess.EMPTY, true);

        assertEquals(Items.DIAMOND, output.getItem());
        assertEquals(0, recipe.invocations);
    }

    private static final class ClientUnsafeRecipe implements CraftingRecipe {
        private final ItemStack result = new ItemStack(Items.DIAMOND);
        private int invocations;

        @Override
        public boolean matches(CraftingContainer container, Level level) {
            return false;
        }

        @Override
        public ItemStack assemble(CraftingContainer container, RegistryAccess access) {
            return result.copy();
        }

        @Override
        public boolean canCraftInDimensions(int width, int height) {
            return true;
        }

        @Override
        public ItemStack getResultItem(RegistryAccess access) {
            invocations++;
            return ClientLevel.class.getName().isEmpty() ? ItemStack.EMPTY : result.copy();
        }

        @Override
        public NonNullList<Ingredient> getIngredients() {
            return NonNullList.of(Ingredient.EMPTY, Ingredient.of(Items.IRON_INGOT));
        }

        @Override
        public ResourceLocation getId() {
            return new ResourceLocation("test", "client_unsafe_result");
        }

        @Override
        public RecipeSerializer<?> getSerializer() {
            return RecipeSerializer.SHAPELESS_RECIPE;
        }

        @Override
        public CraftingBookCategory category() {
            return CraftingBookCategory.MISC;
        }
    }
}
