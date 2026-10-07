package com.huanghuang.rsintegration.mods.ironsspellbooks;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import com.huanghuang.rsintegration.unifiedgrid.UnifiedGridFluidTransfer;
import com.refinedmods.refinedstorage.api.network.INetwork;
import com.refinedmods.refinedstorage.api.util.Action;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AlchemistPotionTerminalTransferTest extends BootstrapTest {
    @BeforeAll static void registerPotionFluid() { InkFluidTestFixtures.ink("potion", 250); }

    @ParameterizedTest @ValueSource(strings = {"REGULAR", "SPLASH", "LINGERING"})
    void emptyCursorBorrowsBottleAndExtractsExactly250Mb(String type) {
        ItemStack potion = AlchemistPotionSupportTest.potion(type);
        FluidStack fluid = AlchemistPotionSupport.fluid(potion);
        INetwork network = network(fluid);
        when(network.extractItem(any(), eq(1), any())).thenReturn(new ItemStack(Items.GLASS_BOTTLE));
        var result = UnifiedGridFluidTransfer.fill(network, ItemStack.EMPTY, fluid);
        assertEquals(250, result.transferred());
        assertTrue(ItemStack.isSameItemSameTags(potion, result.cursor()));
        verify(network).extractItem(argThat(stack -> stack.is(Items.GLASS_BOTTLE)), eq(1), eq(Action.PERFORM));
        verify(network).extractFluid(argThat(fluid::isFluidEqual), eq(250), eq(Action.PERFORM));
    }

    @Test void stackedBottlesConsumeOneAndLeavePotionInOverflow() {
        FluidStack fluid = AlchemistPotionSupport.fluid(AlchemistPotionSupportTest.potion("SPLASH"));
        var result = UnifiedGridFluidTransfer.fill(network(fluid), new ItemStack(Items.GLASS_BOTTLE, 3), fluid);
        assertEquals(2, result.cursor().getCount());
        assertTrue(result.overflow().is(Items.SPLASH_POTION));
        assertEquals(250, result.transferred());
    }

    @Test void specialElixirUsesRuntimeBottleRecipeWithDifferentItemName() {
        FluidStack fluid = InkFluidTestFixtures.ink("greater_healing_elixir", 250);
        InkFluidTestFixtures.ink("greater_healing_potion", 250);
        ItemStack output = new ItemStack(ForgeRegistries.ITEMS.getValue(
                new ResourceLocation("irons_spellbooks", "greater_healing_potion")));
        IronSpellBooksRecipe bottle = IronSpellBooksRecipe.bottleRecipe(new ResourceLocation("test", "elixir_bottle"),
                InkFluidSupport.token(InkFluidTestFixtures.tokenItem(), fluid), output);
        MinecraftServer server = mock(MinecraftServer.class);
        ServerLevel level = mock(ServerLevel.class);
        when(server.overworld()).thenReturn(level);
        INetwork network = network(fluid);
        when(network.extractItem(any(), eq(1), any())).thenReturn(new ItemStack(Items.GLASS_BOTTLE));
        try (MockedStatic<ServerLifecycleHooks> lifecycle = mockStatic(ServerLifecycleHooks.class);
             MockedStatic<IronAlchemistRecipeCatalog> catalog = mockStatic(IronAlchemistRecipeCatalog.class)) {
            lifecycle.when(ServerLifecycleHooks::getCurrentServer).thenReturn(server);
            catalog.when(() -> IronAlchemistRecipeCatalog.allRecipes(level)).thenReturn(List.of(bottle));

            var result = UnifiedGridFluidTransfer.fill(network, ItemStack.EMPTY, fluid);

            assertEquals(250, result.transferred());
            assertTrue(ItemStack.matches(output, result.cursor()));
            verify(network).extractItem(argThat(stack -> stack.is(Items.GLASS_BOTTLE)), eq(1), eq(Action.PERFORM));
            FluidStack tagged = fluid.copy();
            tagged.getOrCreateTag().putString("custom", "not-in-recipe");
            assertFalse(AlchemistBottleSupport.canBottle(tagged));
        }
    }

    @Test void insufficientFluidOrChangedPotionDoesNotCreatePotionAndRefunds() {
        FluidStack fluid = AlchemistPotionSupport.fluid(AlchemistPotionSupportTest.potion("REGULAR"));
        INetwork network = network(fluid);
        FluidStack shortExtract = fluid.copy();
        shortExtract.setAmount(249);
        when(network.extractFluid(any(), eq(250), eq(Action.SIMULATE))).thenReturn(shortExtract);
        assertEquals(0, UnifiedGridFluidTransfer.fill(network, new ItemStack(Items.GLASS_BOTTLE), fluid).transferred());
        verify(network, never()).extractFluid(any(), anyInt(), eq(Action.PERFORM));
        when(network.extractFluid(any(), eq(250), eq(Action.SIMULATE))).thenReturn(fluid.copy());
        FluidStack other = AlchemistPotionSupport.fluid(AlchemistPotionSupportTest.potion("LINGERING"));
        when(network.extractFluid(any(), eq(250), eq(Action.PERFORM))).thenReturn(other);
        when(network.insertFluid(any(), eq(250), eq(Action.PERFORM))).thenReturn(FluidStack.EMPTY);
        var result = UnifiedGridFluidTransfer.fill(network, new ItemStack(Items.GLASS_BOTTLE), fluid);
        assertEquals(0, result.transferred());
        assertTrue(result.cursor().is(Items.GLASS_BOTTLE));
        verify(network).insertFluid(argThat(other::isFluidEqual), eq(250), eq(Action.PERFORM));
    }

    private static INetwork network(FluidStack fluid) {
        INetwork network = mock(INetwork.class);
        when(network.extractFluid(any(), eq(250), any())).thenAnswer(call -> fluid.copy());
        return network;
    }
}
