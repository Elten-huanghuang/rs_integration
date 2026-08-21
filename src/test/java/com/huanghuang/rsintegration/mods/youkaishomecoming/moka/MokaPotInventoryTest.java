package com.huanghuang.rsintegration.mods.youkaishomecoming.moka;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.items.ItemStackHandler;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MokaPotInventoryTest extends BootstrapTest {
    @Test
    void idlePotReturnsEveryResidualSlot() {
        ItemStackHandler handler = new ItemStackHandler(7);
        handler.setStackInSlot(0, new ItemStack(Items.COCOA_BEANS, 2));
        handler.setStackInSlot(4, new ItemStack(Items.MILK_BUCKET));
        handler.setStackInSlot(5, new ItemStack(Items.GLASS_BOTTLE));
        handler.setStackInSlot(6, new ItemStack(Items.HONEY_BOTTLE));
        List<ItemStack> returned = new ArrayList<>();

        assertEquals(5, MokaPotBatchDelegate.evacuateIdleInventory(
                handler, 0, returned::add));
        assertEquals(4, returned.size());
        for (int slot = 0; slot < 7; slot++) {
            assertTrue(handler.getStackInSlot(slot).isEmpty());
        }
    }

    @Test
    void workingPotIsNeverEvacuated() {
        ItemStackHandler handler = new ItemStackHandler(7);
        handler.setStackInSlot(0, new ItemStack(Items.COCOA_BEANS));
        List<ItemStack> returned = new ArrayList<>();

        assertEquals(-1, MokaPotBatchDelegate.evacuateIdleInventory(
                handler, 1, returned::add));
        assertTrue(returned.isEmpty());
        assertEquals(Items.COCOA_BEANS, handler.getStackInSlot(0).getItem());
    }
}
