package com.huanghuang.rsintegration.resonance.backpack;

import com.huanghuang.rsintegration.resonance.disk.ResonanceDiskWrapper;
import com.refinedmods.refinedstorage.api.util.Action;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

public class ResonanceSlot extends Slot {

    private final ResonanceDiskWrapper disk;
    private final int slotIndex;
    private ItemStack lastSnapshot = ItemStack.EMPTY;
    private boolean skipSync;

    public ResonanceSlot(Container container, int index, int x, int y,
                         ResonanceDiskWrapper disk) {
        super(container, index, x, y);
        this.disk = disk;
        this.slotIndex = index;
        this.lastSnapshot = container.getItem(index).copy();
    }

    @Override
    public boolean mayPlace(ItemStack stack) {
        ItemStack remainder = disk.manualInsert(slotIndex, stack, stack.getCount(), Action.SIMULATE);
        return remainder.getCount() < stack.getCount();
    }

    @Override
    public int getMaxStackSize(ItemStack stack) {
        ItemStack remainder = disk.manualInsert(slotIndex, stack, stack.getMaxStackSize(), Action.SIMULATE);
        return stack.getMaxStackSize() - remainder.getCount();
    }

    @Override
    public ItemStack getItem() {
        return this.container.getItem(this.getContainerSlot());
    }

    @Override
    public void set(ItemStack incoming) {
        if (!lastSnapshot.isEmpty()) {
            disk.manualExtract(slotIndex, lastSnapshot.copy(), lastSnapshot.getCount(), 0, Action.PERFORM);
        }
        if (!incoming.isEmpty()) {
            disk.manualInsert(slotIndex, incoming.copy(), incoming.getCount(), Action.PERFORM);
        }
        lastSnapshot = incoming.copy();
        // setChanged() fires from super.set(); suppress the redundant disk write
        skipSync = true;
        super.set(incoming);
    }

    @Override
    public void setChanged() {
        super.setChanged();
        // set() already synced the full swap; this path handles
        // count-only mutations (grow/shrink in place) that bypass set().
        if (skipSync) {
            skipSync = false;
            return;
        }
        ItemStack current = this.container.getItem(this.getContainerSlot());

        if (ItemStack.matches(lastSnapshot, current)) {
            int delta = current.getCount() - lastSnapshot.getCount();
            if (delta > 0) {
                ItemStack ins = current.copy();
                ins.setCount(delta);
                disk.manualInsert(slotIndex, ins, delta, Action.PERFORM);
            } else if (delta < 0) {
                ItemStack ext = lastSnapshot.copy();
                ext.setCount(-delta);
                disk.manualExtract(slotIndex, ext, -delta, 0, Action.PERFORM);
            }
        } else {
            if (!lastSnapshot.isEmpty())
                disk.manualExtract(slotIndex, lastSnapshot.copy(), lastSnapshot.getCount(), 0, Action.PERFORM);
            if (!current.isEmpty())
                disk.manualInsert(slotIndex, current.copy(), current.getCount(), Action.PERFORM);
        }
        lastSnapshot = current.copy();
    }
}
