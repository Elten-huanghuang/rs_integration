package com.huanghuang.rsintegration.mods.aetherworks;

import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraphProjector;
import com.huanghuang.rsintegration.mods.ironsspellbooks.InkFluidSupport;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import com.rekindled.embers.recipe.FluidIngredient;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.fluids.FluidStack;
import net.sirplop.aetherworks.recipe.MetalFormerRecipe;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AetherworksMetalFormerRecipeHandlerTest extends BootstrapTest {
    private final AetherworksMetalFormerRecipeHandler handler = new AetherworksMetalFormerRecipeHandler();

    @Test
    void nativeRecipeProjectsItemAndExactFluidAmountIntoRecursiveGraph() {
        MetalFormerRecipe recipe = recipe(Ingredient.of(Items.DIAMOND), 360,
                new ItemStack(Items.EMERALD, 2), false);
        try (MockedStatic<InkFluidSupport> tokens = mockStatic(InkFluidSupport.class)) {
            tokens.when(() -> InkFluidSupport.token(any(FluidStack.class)))
                    .thenAnswer(call -> token(call.getArgument(0)));
            assertTrue(handler.canHandle(recipe));
            assertTrue(handler.supportsIntermediateProjection(recipe));
            var ingredients = handler.getIngredients(recipe);
            assertNotNull(ingredients);
            assertEquals(2, ingredients.size());
            assertEquals(1, ingredients.get(0).count());
            assertEquals(360, ingredients.get(1).count());
            assertEquals(2880, handler.requiredIngredientCount(recipe, ingredients.get(1), 1, 8));
            var node = ImmutableRecipeGraphProjector.projectRecipe(recipe.getId(),
                    handler.getResultItem(recipe, RegistryAccess.EMPTY), ingredients,
                    "aetherworks_metal_former", new ResourceLocation("aetherworks", "metal_forming"));
            assertNotNull(node);
            assertEquals(2, node.outputCount());
            assertEquals(360, node.inputs().get(1).count());
        }
    }

    @Test
    void exactInputNbtAndOutputNbtArePreserved() {
        ItemStack input = new ItemStack(Items.POTION);
        input.getOrCreateTag().putString("Potion", "minecraft:water");
        ItemStack output = input.copy();
        output.getOrCreateTag().putInt("gem_color", -8210433);
        MetalFormerRecipe recipe = recipe(Ingredient.of(input), 810, output, true);
        try (MockedStatic<InkFluidSupport> tokens = mockStatic(InkFluidSupport.class)) {
            tokens.when(() -> InkFluidSupport.token(any(FluidStack.class)))
                    .thenAnswer(call -> token(call.getArgument(0)));
            var ingredients = handler.getIngredients(recipe);
            assertNotNull(ingredients);
            assertTrue(ingredients.get(0).ingredient().test(input));
            assertFalse(ingredients.get(0).ingredient().test(new ItemStack(Items.POTION)));
            ItemStack returned = handler.getResultItem(recipe, RegistryAccess.EMPTY);
            assertEquals(-8210433, returned.getTag().getInt("gem_color"));
            returned.getTag().putInt("gem_color", 0);
            assertEquals(-8210433, handler.getResultItem(recipe, RegistryAccess.EMPTY)
                    .getTag().getInt("gem_color"));
        }
    }

    @Test
    void unsupportedFluidAlternativesDoNotBecomeAnItemOnlyRecipe() {
        FluidIngredient fluid = mock(FluidIngredient.class);
        when(fluid.getFluids()).thenReturn(List.of(new FluidStack(Fluids.WATER, 90),
                new FluidStack(Fluids.LAVA, 180)));
        MetalFormerRecipe recipe = new MetalFormerRecipe(new ResourceLocation("aetherworks", "bad_amounts"),
                Ingredient.of(Items.IRON_INGOT), fluid, 2100, 200, new ItemStack(Items.GOLD_INGOT), false);
        assertNull(handler.getIngredients(recipe));
        assertFalse(handler.supportsIntermediateProjection(recipe));
    }

    private static MetalFormerRecipe recipe(Ingredient input, int amount, ItemStack output, boolean exact) {
        FluidIngredient fluid = mock(FluidIngredient.class);
        when(fluid.getFluids()).thenReturn(List.of(new FluidStack(Fluids.WATER, amount)));
        return new MetalFormerRecipe(new ResourceLocation("aetherworks", "metal_former/test"),
                input, fluid, 2600, 300, output, exact);
    }

    private static ItemStack token(FluidStack fluid) {
        ItemStack result = new ItemStack(Items.POTION, fluid.getAmount());
        FluidStack identity = fluid.copy();
        identity.setAmount(1);
        result.setTag(identity.writeToNBT(new CompoundTag()));
        return result;
    }
}
