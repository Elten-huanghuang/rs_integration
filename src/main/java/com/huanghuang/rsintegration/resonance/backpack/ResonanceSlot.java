package com.huanghuang.rsintegration.resonance.backpack;

import com.huanghuang.rsintegration.resonance.disk.ResonanceDiskWrapper;

import net.minecraft.world.Container;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

public class ResonanceSlot extends Slot {

    private final ResonanceDiskInventory inventory;

    public ResonanceSlot(Container container, int index, int x, int y) {
        super(container, index, x, y);
        this.inventory = (ResonanceDiskInventory) container;
    }

    @Override
    public boolean mayPlace(ItemStack stack) {
        return inventory.simulateAccept(getContainerSlot(), stack) > 0;
    }

    @Override
    public int getMaxStackSize(ItemStack stack) {
        return Math.min(ResonanceDiskWrapper.isLogicallyNonStackable(stack) ? 1 : stack.getMaxStackSize(),
                inventory.getItem(getContainerSlot()).getCount()
                + inventory.simulateAccept(getContainerSlot(), stack));
    }
}
