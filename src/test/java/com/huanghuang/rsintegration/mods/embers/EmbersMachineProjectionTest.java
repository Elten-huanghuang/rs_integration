package com.huanghuang.rsintegration.mods.embers;

import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraphProjector;
import com.huanghuang.rsintegration.mods.ironsspellbooks.InkFluidSupport;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import com.rekindled.embers.recipe.FluidIngredient;
import com.rekindled.embers.recipe.MeltingRecipe;
import com.rekindled.embers.recipe.MixingRecipe;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.fluids.FluidStack;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EmbersMachineProjectionTest extends BootstrapTest {
    @Test
    void nativeMeltingRecipeKeepsProducedFluidAmount() {
        MeltingRecipe recipe = new MeltingRecipe(
                new ResourceLocation("embers", "melting/copper_ingot"),
                Ingredient.of(Items.COPPER_INGOT), new FluidStack(Fluids.WATER, 144));
        EmbersMeltingRecipeHandler handler = new EmbersMeltingRecipeHandler();

        assertTrue(handler.supportsBackgroundPlanning(recipe));
        ImmutableRecipeGraph.RecipeNode projected = ImmutableRecipeGraphProjector.projectRecipe(
                recipe.id, new ItemStack(Items.POTION, 144), handler.getIngredients(recipe),
                "embers_melter", new ResourceLocation("embers", "melting"));
        assertNotNull(projected);
        assertEquals(144, projected.outputCount());
        assertEquals(1, projected.inputs().get(0).count());
    }

    @Test
    void nativeMixingRecipePreservesFluidVolumesInBackgroundGraph() {
        FluidIngredient water = mock(FluidIngredient.class);
        FluidIngredient lava = mock(FluidIngredient.class);
        when(water.getFluids()).thenReturn(List.of(new FluidStack(Fluids.WATER, 2000)));
        when(lava.getFluids()).thenReturn(List.of(new FluidStack(Fluids.LAVA, 2000)));
        MixingRecipe recipe = new MixingRecipe(
                new ResourceLocation("embers", "mixing/molten_dawnstone"),
                new ArrayList<>(List.of(water, lava)),
                new FluidStack(Fluids.WATER, 4000));
        EmbersMixingRecipeHandler handler = new EmbersMixingRecipeHandler();

        try (MockedStatic<InkFluidSupport> tokens = mockStatic(InkFluidSupport.class)) {
            tokens.when(() -> InkFluidSupport.token(any(FluidStack.class)))
                    .thenAnswer(call -> fluidToken(call.getArgument(0)));
            assertTrue(handler.supportsBackgroundPlanning(recipe));
            List<IngredientSpec> ingredients = handler.getIngredients(recipe);
            ImmutableRecipeGraph.RecipeNode projected = ImmutableRecipeGraphProjector.projectRecipe(
                    recipe.id, fluidToken(new FluidStack(Fluids.WATER, 4000)), ingredients,
                    "embers_mixer", new ResourceLocation("embers", "mixing"));

            assertNotNull(projected);
            assertEquals(4000, projected.outputCount());
            assertEquals(2, projected.inputs().size());
            assertEquals(2000, projected.inputs().get(0).count());
            assertEquals(2000, projected.inputs().get(1).count());
        }
    }

    private static ItemStack fluidToken(FluidStack fluid) {
        ItemStack token = new ItemStack(Items.POTION, fluid.getAmount());
        FluidStack identity = fluid.copy();
        identity.setAmount(1);
        token.setTag(identity.writeToNBT(new CompoundTag()));
        return token;
    }
}
