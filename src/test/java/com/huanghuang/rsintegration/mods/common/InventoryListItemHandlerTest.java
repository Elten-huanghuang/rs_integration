package com.huanghuang.rsintegration.mods.common;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.core.NonNullList;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.items.IItemHandler;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InventoryListItemHandlerTest extends BootstrapTest {

    @Test
    void emptySlotSimulationReportsTheCapacityThatWouldAcceptTheStack() {
        NonNullList<ItemStack> items = NonNullList.withSize(1, ItemStack.EMPTY);
        IItemHandler handler = InventoryListItemHandler.of(() -> items);
        ItemStack input = new ItemStack(Items.STONE, 16);

        ItemStack remainder = handler.insertItem(0, input, true);

        assertTrue(remainder.isEmpty());
        assertTrue(items.get(0).isEmpty());
        assertEquals(16, input.getCount());
    }

    @Test
    void realInsertionIntoAnEmptySlotReplacesItWithTheCopiedStack() {
        NonNullList<ItemStack> items = NonNullList.withSize(1, ItemStack.EMPTY);
        IItemHandler handler = InventoryListItemHandler.of(() -> items);

        ItemStack remainder = handler.insertItem(0, new ItemStack(Items.STONE, 4), false);

        assertTrue(remainder.isEmpty());
        assertEquals(4, items.get(0).getCount());
    }
}
