package com.huanghuang.rsintegration.resonance.backpack;

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
        return inventory.getItem(getContainerSlot()).getCount()
                + inventory.simulateAccept(getContainerSlot(), stack);
    }
}
