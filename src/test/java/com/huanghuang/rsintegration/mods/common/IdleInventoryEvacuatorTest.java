package com.huanghuang.rsintegration.mods.common;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.items.ItemStackHandler;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IdleInventoryEvacuatorTest extends BootstrapTest {
    @Test
    void handlerReturnsRealItemsDiscardsPreviewAndPreservesFuel() {
        ItemStackHandler inventory = new ItemStackHandler(4);
        inventory.setStackInSlot(0, new ItemStack(Items.PAPER, 2));
        inventory.setStackInSlot(1, new ItemStack(Items.MAP));
        inventory.setStackInSlot(2, new ItemStack(Items.LAPIS_LAZULI, 8));
        List<ItemStack> returned = new ArrayList<>();

        IdleInventoryEvacuator.Result result = IdleInventoryEvacuator.evacuate(
                inventory, true,
                slot -> slot == 0 ? IdleInventoryEvacuator.SlotPolicy.RETURN
                        : slot == 1 ? IdleInventoryEvacuator.SlotPolicy.DISCARD
                        : IdleInventoryEvacuator.SlotPolicy.PRESERVE,
                returned::add);

        assertTrue(result.cleared());
        assertEquals(2, result.returnedCount());
        assertEquals(1, returned.size());
        assertTrue(inventory.getStackInSlot(0).isEmpty());
        assertTrue(inventory.getStackInSlot(1).isEmpty());
        assertEquals(8, inventory.getStackInSlot(2).getCount());
    }

    @Test
    void busyContainerIsNotMutated() {
        SimpleContainer inventory = new SimpleContainer(new ItemStack(Items.APPLE));
        List<ItemStack> returned = new ArrayList<>();

        IdleInventoryEvacuator.Result result = IdleInventoryEvacuator.evacuate(
                inventory, false, ignored -> IdleInventoryEvacuator.SlotPolicy.RETURN,
                returned::add);

        assertFalse(result.cleared());
        assertTrue(returned.isEmpty());
        assertEquals(Items.APPLE, inventory.getItem(0).getItem());
    }
}
