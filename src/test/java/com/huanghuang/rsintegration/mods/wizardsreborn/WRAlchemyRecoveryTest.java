package com.huanghuang.rsintegration.mods.wizardsreborn;

import com.huanghuang.rsintegration.crafting.CraftStorageEndpoint;
import com.huanghuang.rsintegration.mods.ironsspellbooks.InkFluidSupport;
import com.huanghuang.rsintegration.mods.ironsspellbooks.InkFluidTestFixtures;
import com.huanghuang.rsintegration.storage.StorageOperationMode;
import com.huanghuang.rsintegration.storage.StorageOperationResult;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.fluids.capability.templates.FluidTank;
import net.minecraftforge.items.ItemStackHandler;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WRAlchemyRecoveryTest extends BootstrapTest {
    private final ServerPlayer player = mock(ServerPlayer.class);
    private final CraftStorageEndpoint endpoint = mock(CraftStorageEndpoint.class);

    @Test
    void returnsAllOldInputsOutputsAndFourTanksToStorage() {
        ItemStackHandler input = new ItemStackHandler(6);
        ItemStackHandler output = new ItemStackHandler(1);
        input.setStackInSlot(0, new ItemStack(Items.IRON_INGOT, 13));
        input.setStackInSlot(5, new ItemStack(Items.GOLD_INGOT, 3));
        output.setStackInSlot(0, new ItemStack(Items.DIAMOND, 2));
        List<IFluidHandler> tanks = new ArrayList<>();
        for (int index = 0; index < 4; index++) {
            FluidTank tank = new FluidTank(5000);
            FluidStack fluid = new FluidStack(index == 3 ? Fluids.LAVA : Fluids.WATER, 500 + index);
            fluid.getOrCreateTag().putInt("tank", index);
            tank.setFluid(fluid);
            tanks.add(tank);
        }
        List<ItemStack> stored = new ArrayList<>();
        when(endpoint.insert(eq(player), any(ItemStack.class), anyBoolean())).thenAnswer(call -> {
            ItemStack stack = call.getArgument(1);
            boolean simulate = call.getArgument(2);
            if (!simulate) stored.add(stack.copy());
            return result(stack, ItemStack.EMPTY, simulate);
        });

        assertTrue(WRAlchemyRecovery.recover(List.of(input, output), tanks, endpoint, player, this::token));
        assertEquals(7, stored.size());
        assertEquals(13, stored.get(0).getCount());
        assertEquals(3, stored.get(1).getCount());
        assertEquals(2, stored.get(2).getCount());
        for (int index = 0; index < 4; index++) {
            FluidStack fluid = InkFluidSupport.fluid(stored.get(index + 3));
            assertEquals(500 + index, fluid.getAmount());
            assertEquals(index, fluid.getTag().getInt("tank"));
            assertTrue(tanks.get(index).getFluidInTank(0).isEmpty());
        }
        for (int slot = 0; slot < input.getSlots(); slot++) assertTrue(input.getStackInSlot(slot).isEmpty());
        assertTrue(output.getStackInSlot(0).isEmpty());
    }

    @Test
    void rejectedFluidPreflightLeavesAllOldContentsInPlace() {
        ItemStackHandler input = new ItemStackHandler(6);
        input.setStackInSlot(0, new ItemStack(Items.IRON_INGOT, 13));
        FluidTank tank = new FluidTank(5000);
        tank.setFluid(new FluidStack(Fluids.WATER, 1000));
        when(endpoint.insert(eq(player), any(ItemStack.class), eq(true))).thenAnswer(call -> {
            ItemStack stack = call.getArgument(1);
            return result(stack, InkFluidSupport.isToken(stack) ? stack : ItemStack.EMPTY, true);
        });

        assertFalse(WRAlchemyRecovery.recover(List.of(input), List.of(tank), endpoint, player, this::token));
        assertEquals(13, input.getStackInSlot(0).getCount());
        assertEquals(1000, tank.getFluidAmount());
        verify(endpoint, never()).insert(any(), any(), eq(false));
    }

    @Test
    void partialItemInsertionKeepsExactRemainderInOriginalSlot() {
        ItemStackHandler input = new ItemStackHandler(6);
        input.setStackInSlot(0, new ItemStack(Items.IRON_INGOT, 13));
        input.setStackInSlot(1, new ItemStack(Items.GOLD_INGOT, 3));
        acceptSimulation();
        when(endpoint.insert(eq(player), any(ItemStack.class), eq(false))).thenAnswer(call ->
                result(call.getArgument(1), ((ItemStack) call.getArgument(1)).copyWithCount(5), false));

        assertFalse(WRAlchemyRecovery.recover(List.of(input), List.of(), endpoint, player, this::token));
        assertEquals(5, input.getStackInSlot(0).getCount());
        assertEquals(3, input.getStackInSlot(1).getCount());
        verify(endpoint, times(1)).insert(any(), any(), eq(false));
    }

    @Test
    void partialFluidInsertionRestoresAmountAndNbtToOriginalTank() {
        FluidTank tank = new FluidTank(5000);
        FluidStack water = new FluidStack(Fluids.WATER, 1000);
        water.getOrCreateTag().putString("variant", "old");
        tank.setFluid(water.copy());
        acceptSimulation();
        when(endpoint.insert(eq(player), any(ItemStack.class), eq(false))).thenAnswer(call ->
                result(call.getArgument(1), ((ItemStack) call.getArgument(1)).copyWithCount(400), false));

        assertFalse(WRAlchemyRecovery.recover(List.of(), List.of(tank), endpoint, player, this::token));
        assertEquals(400, tank.getFluidAmount());
        assertTrue(water.isFluidEqual(tank.getFluid()));
        assertEquals(water.getTag(), tank.getFluid().getTag());
    }

    private void acceptSimulation() {
        when(endpoint.insert(eq(player), any(ItemStack.class), eq(true))).thenAnswer(call ->
                result(call.getArgument(1), ItemStack.EMPTY, true));
    }

    private static StorageOperationResult result(ItemStack input, ItemStack remainder, boolean simulate) {
        return StorageOperationResult.inserted(simulate ? StorageOperationMode.SIMULATE
                : StorageOperationMode.PERFORM, input, remainder);
    }

    private ItemStack token(FluidStack fluid) {
        return InkFluidSupport.token(InkFluidTestFixtures.tokenItem(), fluid);
    }
}
