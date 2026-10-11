package com.huanghuang.rsintegration.mods.embers;

import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraphProjector;
import com.huanghuang.rsintegration.mods.ironsspellbooks.InkFluidSupport;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import com.rekindled.embers.recipe.BoilingRecipe;
import com.rekindled.embers.recipe.FluidHandlerContext;
import com.rekindled.embers.recipe.FluidIngredient;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.templates.FluidTank;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class EmbersBoilingRecipeHandlerTest extends BootstrapTest {
    private final EmbersBoilingRecipeHandler handler = new EmbersBoilingRecipeHandler();

    @Test
    void boilingProjectsExactFluidRatioAndKeepsFluidNbt() {
        FluidStack output = new FluidStack(Fluids.LAVA, 500);
        output.setTag(new CompoundTag());
        output.getTag().putString("grade", "test");
        BoilingRecipe recipe = new BoilingRecipe(new ResourceLocation("embers", "boiling/test"),
                input(100), output);
        try (MockedStatic<InkFluidSupport> tokens = tokens()) {
            assertTrue(handler.supportsIntermediateProjection(recipe));
            var specs = handler.getIngredients(recipe);
            assertEquals(100, specs.get(0).count());
            ItemStack result = handler.getResultItem(recipe, RegistryAccess.EMPTY);
            assertEquals(500, result.getCount());
            assertEquals("test", FluidStack.loadFluidStackFromNBT(result.getTag()).getTag().getString("grade"));
            var graph = ImmutableRecipeGraphProjector.projectRecipe(recipe.getId(), result, specs,
                    "embers_mini_boiler", new ResourceLocation("embers", "boiling"));
            assertNotNull(graph);
            assertEquals(500, graph.outputCount());
            assertEquals(100, graph.inputs().get(0).count());
        }
    }

    @Test
    void nativeProcessingConsumesOnlyReservedInputEvenWithLargeHeatRequest() {
        for (int amount : new int[]{1, 100}) {
            BoilingRecipe recipe = new BoilingRecipe(new ResourceLocation("embers", "boiling/test"),
                    input(amount), new FluidStack(Fluids.LAVA, amount * 5));
            FluidTank tank = new FluidTank(1000);
            tank.setFluid(new FluidStack(Fluids.WATER, amount));
            FluidHandlerContext context = new FluidHandlerContext(tank);
            assertTrue(recipe.matches(context, null));
            assertEquals(amount * 5, recipe.process(context, 1000).getAmount());
            assertTrue(tank.isEmpty());
        }
    }

    @Test
    void invalidAlternativeAmountsAreNotProjectedAsAFluidRecipe() {
        FluidIngredient input = mock(FluidIngredient.class);
        when(input.getFluids()).thenReturn(List.of(new FluidStack(Fluids.WATER, 1),
                new FluidStack(Fluids.LAVA, 2)));
        BoilingRecipe recipe = new BoilingRecipe(new ResourceLocation("embers", "boiling/invalid"),
                input, new FluidStack(Fluids.LAVA, 5));
        assertNull(handler.getIngredients(recipe));
        assertFalse(handler.supportsIntermediateProjection(recipe));
    }

    static FluidIngredient input(int amount) {
        return FluidIngredient.of(Fluids.WATER, amount);
    }

    static MockedStatic<InkFluidSupport> tokens() {
        MockedStatic<InkFluidSupport> tokens = mockStatic(InkFluidSupport.class);
        tokens.when(() -> InkFluidSupport.token(any(FluidStack.class)))
                .thenAnswer(call -> token(call.getArgument(0)));
        tokens.when(() -> InkFluidSupport.fluid(any(ItemStack.class))).thenAnswer(call -> {
            ItemStack token = call.getArgument(0);
            if (token.isEmpty() || token.getTag() == null) return FluidStack.EMPTY;
            FluidStack fluid = FluidStack.loadFluidStackFromNBT(token.getTag());
            fluid.setAmount(token.getCount());
            return fluid;
        });
        return tokens;
    }

    static ItemStack token(FluidStack fluid) {
        if (fluid.isEmpty()) return ItemStack.EMPTY;
        ItemStack token = new ItemStack(Items.POTION, fluid.getAmount());
        FluidStack identity = fluid.copy();
        identity.setAmount(1);
        token.setTag(identity.writeToNBT(new CompoundTag()));
        return token;
    }
}
