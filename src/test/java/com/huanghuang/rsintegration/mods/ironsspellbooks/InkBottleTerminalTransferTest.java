package com.huanghuang.rsintegration.mods.ironsspellbooks;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import com.huanghuang.rsintegration.unifiedgrid.UnifiedGridFluidTransfer;
import com.refinedmods.refinedstorage.api.network.INetwork;
import com.refinedmods.refinedstorage.api.util.Action;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.registries.ForgeRegistries;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class InkBottleTerminalTransferTest extends BootstrapTest {
    @ParameterizedTest
    @ValueSource(strings = {"common_ink", "uncommon_ink", "rare_ink", "epic_ink", "legendary_ink"})
    void heldBottleExtractsOneMatchingInkAndExactly250Mb(String quality) {
        Store store = new Store(quality, 750, 0);
        var result = UnifiedGridFluidTransfer.fill(store.network, new ItemStack(Items.GLASS_BOTTLE), store.fluid);
        assertTrue(result.cursor().is(ForgeRegistries.ITEMS.getValue(ForgeRegistries.FLUIDS.getKey(store.fluid.getFluid()))));
        assertEquals(1, result.cursor().getCount());
        assertEquals(250, result.transferred());
        assertEquals(500, store.amount);
        assertTrue(result.overflow().isEmpty());
        verify(store.network, never()).extractItem(any(), anyInt(), any());
    }

    @Test void stackedBottlesConsumeOneAndProduceOneOverflowInk() {
        Store store = new Store("rare_ink", 500, 0);
        ItemStack bottles = new ItemStack(Items.GLASS_BOTTLE, 3);
        var result = UnifiedGridFluidTransfer.fill(store.network, bottles, store.fluid);
        assertTrue(result.cursor().is(Items.GLASS_BOTTLE));
        assertEquals(2, result.cursor().getCount());
        assertEquals(1, result.overflow().getCount());
        assertEquals(3, bottles.getCount(), "操作只替换副本");
        assertEquals(250, store.amount);
    }

    @Test void lessThan250MbDoesNotConsumeHeldOrNetworkBottle() {
        Store store = new Store("epic_ink", 249, 1);
        assertEquals(0, UnifiedGridFluidTransfer.fill(store.network, new ItemStack(Items.GLASS_BOTTLE), store.fluid).transferred());
        assertEquals(0, UnifiedGridFluidTransfer.fill(store.network, ItemStack.EMPTY, store.fluid).transferred());
        assertEquals(249, store.amount);
        assertEquals(1, store.bottles);
        verify(store.network, never()).extractFluid(any(), anyInt(), eq(Action.PERFORM));
        verify(store.network, never()).extractItem(any(), anyInt(), eq(Action.PERFORM));
    }

    @Test void emptyCursorBorrowsGlassBottleNotBucketFromNetwork() {
        Store store = new Store("common_ink", 250, 1);
        var result = UnifiedGridFluidTransfer.fill(store.network, ItemStack.EMPTY, store.fluid);
        assertEquals(250, result.transferred());
        assertEquals(0, store.bottles);
        assertEquals(0, store.amount);
        verify(store.network).extractItem(argThat(stack -> stack.is(Items.GLASS_BOTTLE)), eq(1), eq(Action.PERFORM));
    }

    @Test void emptyCursorWithoutNetworkBottleLeavesInkUntouched() {
        Store store = new Store("common_ink", 250, 0);
        assertEquals(0, UnifiedGridFluidTransfer.fill(store.network, ItemStack.EMPTY, store.fluid).transferred());
        assertEquals(250, store.amount);
    }

    @Test void partialRealExtractionRefundsInkAndNetworkBottle() {
        Store store = new Store("legendary_ink", 250, 1);
        store.extractLimit = 100;
        var result = UnifiedGridFluidTransfer.fill(store.network, ItemStack.EMPTY, store.fluid);
        assertEquals(0, result.transferred());
        assertTrue(result.cursor().isEmpty());
        assertTrue(result.recovery().isEmpty());
        assertEquals(250, store.amount);
        assertEquals(1, store.bottles);
    }

    @Test void rejectedRefundRetainsBottleAndRecoverableFluid() {
        Store store = new Store("rare_ink", 250, 1);
        store.extractLimit = 100;
        store.rejectRefund = true;
        var result = UnifiedGridFluidTransfer.fill(store.network, ItemStack.EMPTY, store.fluid);
        assertEquals(0, result.transferred());
        assertTrue(result.cursor().is(Items.GLASS_BOTTLE));
        assertEquals(100, result.recovery().getAmount());
        assertEquals(250, store.amount + result.recovery().getAmount());
    }

    @Test void changedFluidIdentityIsRefundedWithoutCreatingInk() {
        Store store = new Store("common_ink", 250, 0);
        when(store.network.extractFluid(any(), anyInt(), eq(Action.PERFORM)))
                .thenReturn(new FluidStack(Fluids.WATER, 250));
        when(store.network.insertFluid(any(), anyInt(), eq(Action.PERFORM))).thenReturn(FluidStack.EMPTY);
        var result = UnifiedGridFluidTransfer.fill(store.network, new ItemStack(Items.GLASS_BOTTLE), store.fluid);
        assertTrue(result.cursor().is(Items.GLASS_BOTTLE));
        assertEquals(0, result.transferred());
        verify(store.network).insertFluid(argThat(fluid -> fluid.getFluid() == Fluids.WATER), eq(250), eq(Action.PERFORM));
    }

    @Test void unrelatedOrTaggedFluidCannotBeBottledAsInk() {
        Store store = new Store("common_ink", 250, 0);
        assertEquals(0, UnifiedGridFluidTransfer.fill(store.network, new ItemStack(Items.GLASS_BOTTLE),
                new FluidStack(Fluids.LAVA, 1000)).transferred());
        store.fluid.getOrCreateTag().putString("custom", "test");
        assertEquals(0, UnifiedGridFluidTransfer.fill(store.network, new ItemStack(Items.GLASS_BOTTLE), store.fluid).transferred());
        verify(store.network, never()).extractFluid(any(), anyInt(), eq(Action.PERFORM));
    }

    private static final class Store {
        final INetwork network = mock(INetwork.class);
        final FluidStack fluid;
        int amount, bottles, extractLimit = Integer.MAX_VALUE;
        boolean rejectRefund;
        Store(String quality, int amount, int bottles) {
            this.fluid = InkFluidTestFixtures.ink(quality, amount);
            this.amount = amount;
            this.bottles = bottles;
            when(network.extractFluid(any(), anyInt(), any())).thenAnswer(call -> {
                int extracted = Math.min(this.amount, call.getArgument(1));
                if (call.getArgument(2) == Action.PERFORM) {
                    extracted = Math.min(extracted, extractLimit);
                    this.amount -= extracted;
                }
                FluidStack result = fluid.copy();
                result.setAmount(extracted);
                return result;
            });
            when(network.insertFluid(any(), anyInt(), any())).thenAnswer(call -> {
                FluidStack offered = call.getArgument(0);
                if (rejectRefund) return offered.copy();
                this.amount += (int) call.getArgument(1);
                return FluidStack.EMPTY;
            });
            when(network.extractItem(any(), anyInt(), any())).thenAnswer(call -> {
                ItemStack template = call.getArgument(0);
                if (!template.is(Items.GLASS_BOTTLE) || this.bottles == 0) return ItemStack.EMPTY;
                if (call.getArgument(2) == Action.PERFORM) this.bottles--;
                return new ItemStack(Items.GLASS_BOTTLE);
            });
            when(network.insertItem(any(), anyInt(), any())).thenAnswer(call -> {
                ItemStack offered = call.getArgument(0);
                if (rejectRefund) return offered.copy();
                this.bottles += offered.getCount();
                return ItemStack.EMPTY;
            });
        }
    }
}
