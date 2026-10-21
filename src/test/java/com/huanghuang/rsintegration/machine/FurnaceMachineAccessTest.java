package com.huanghuang.rsintegration.machine;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FurnaceMachineAccessTest extends BootstrapTest {
    @Test
    void extractsAndClearsVanillaFurnaceOutput() {
        AbstractFurnaceBlockEntity furnace =
                mock(AbstractFurnaceBlockEntity.class);
        when(furnace.getItem(FurnaceMachineAccess.OUTPUT_SLOT))
                .thenReturn(new ItemStack(Items.IRON_INGOT, 2));

        FurnaceMachineAccess.Result result =
                FurnaceMachineAccess.extractOutput(furnace);

        assertTrue(result.success());
        assertEquals(Items.IRON_INGOT, result.stack().getItem());
        assertEquals(2, result.stack().getCount());
        verify(furnace).setItem(
                FurnaceMachineAccess.OUTPUT_SLOT, ItemStack.EMPTY);
        verify(furnace).setChanged();
    }

    @Test
    void insertsOnlyTheAcceptedCarriedAmount() {
        AbstractContainerMenu menu = mock(AbstractContainerMenu.class);
        AbstractFurnaceBlockEntity furnace =
                mock(AbstractFurnaceBlockEntity.class);
        when(menu.getCarried()).thenReturn(new ItemStack(Items.IRON_INGOT, 2));
        when(furnace.getItem(FurnaceMachineAccess.INPUT_SLOT))
                .thenReturn(ItemStack.EMPTY);
        when(furnace.getMaxStackSize()).thenReturn(64);

        FurnaceMachineAccess.Result result =
                FurnaceMachineAccess.insertCarried(
                        menu, furnace, MachineSlotType.INPUT);

        assertTrue(result.success());
        verify(furnace).setItem(
                eq(FurnaceMachineAccess.INPUT_SLOT),
                argThat(stack -> stack.is(Items.IRON_INGOT)
                        && stack.getCount() == 2));
        verify(menu).setCarried(argThat(ItemStack::isEmpty));
        verify(furnace).setChanged();
    }
}
