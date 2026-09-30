package com.huanghuang.rsintegration.mods.biomancy;

import com.huanghuang.rsintegration.crafting.batch.IBatchDelegate.CraftPhase;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

class BiomancyBatchDelegateTest extends BootstrapTest {
    public interface MachineInventories {
        Object getInputInventory();
        Object getOutputInventory();
    }
    public record MachineInventory(ItemStack stack) {
        public int getSlots() { return 1; }
        public ItemStack getStackInSlot(int slot) { return stack; }
    }

    @Test
    void nativeResultSlotConsumesExactlyOnceWithoutShiftingIntoPlayerInventory() {
        AbstractContainerMenu menu = mock(AbstractContainerMenu.class);
        ServerPlayer player = mock(ServerPlayer.class);
        SimpleContainer output = new SimpleContainer(new ItemStack(Items.DIAMOND, 2));
        AtomicInteger takes = new AtomicInteger();
        Slot result = new Slot(output, 0, 0, 0) {
            @Override
            public void onTake(Player player, ItemStack stack) {
                takes.incrementAndGet();
                assertEquals(2, stack.getCount());
                super.onTake(player, stack);
            }
        };
        when(menu.getSlot(37)).thenReturn(result);

        ItemStack taken = BiomancyBatchDelegate.takeBioForgeResult(menu, player);
        assertEquals(Items.DIAMOND, taken.getItem());
        assertEquals(2, taken.getCount());
        assertEquals(1, takes.get());
        assertTrue(output.isEmpty());
        assertTrue(BiomancyBatchDelegate.takeBioForgeResult(menu, player).isEmpty());
        assertEquals(1, takes.get());
        verify(menu, never()).quickMoveStack(player, 37);
    }

    @Test
    void resultSlotRespectsNativePickupConditions() {
        AbstractContainerMenu menu = mock(AbstractContainerMenu.class);
        ServerPlayer player = mock(ServerPlayer.class);
        SimpleContainer output = new SimpleContainer(new ItemStack(Items.DIAMOND));
        Slot result = new Slot(output, 0, 0, 0) {
            @Override
            public boolean mayPickup(Player player) { return false; }
        };
        when(menu.getSlot(37)).thenReturn(result);
        assertTrue(BiomancyBatchDelegate.takeBioForgeResult(menu, player).isEmpty());
        assertEquals(1, output.getItem(0).getCount());
    }

    @Test
    void completedCraftDoesNotRequestFuelAgain() {
        BlockEntity machine = mock(BlockEntity.class, withSettings().extraInterfaces(MachineInventories.class));
        when(((MachineInventories) machine).getInputInventory()).thenReturn(new MachineInventory(ItemStack.EMPTY));
        when(((MachineInventories) machine).getOutputInventory()).thenReturn(
                new MachineInventory(new ItemStack(Items.DIAMOND)));
        BiomancyBatchDelegate delegate = new BiomancyBatchDelegate();
        assertEquals(CraftPhase.WORKING, delegate.observeMachineCraft(mock(ServerLevel.class), machine).phase());
        assertEquals(CraftPhase.DONE, delegate.observeMachineCraft(mock(ServerLevel.class), machine).phase());
    }

    @Test
    void delegateCollectsActualByproductsAndCannotRefundMissingInputs() {
        BiomancyBatchDelegate delegate = new BiomancyBatchDelegate();
        assertTrue(delegate.collectsPhysicalSecondaryOutputs());
        assertFalse(delegate.isFailureRefundSafe());
    }
}
