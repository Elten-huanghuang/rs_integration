package com.huanghuang.rsintegration.resonance.backpack;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.resonance.disk.ResonanceDiskWrapper;
import com.refinedmods.refinedstorage.api.util.Action;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

public class ResonanceSlot extends Slot {

    private final ResonanceDiskWrapper disk;
    private final int slotIndex;
    private ItemStack lastSnapshot = ItemStack.EMPTY;

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
        RSIntegrationMod.LOGGER.info("[RSI-Slot] set() slot={} lastSnapshot={} incoming={} diskStored={}",
                slotIndex,
                lastSnapshot.isEmpty() ? "EMPTY" : lastSnapshot.getDisplayName().getString() + " x" + lastSnapshot.getCount(),
                incoming.isEmpty() ? "EMPTY" : incoming.getDisplayName().getString() + " x" + incoming.getCount(),
                disk.getStored());

        if (!lastSnapshot.isEmpty()) {
            disk.manualExtract(slotIndex, lastSnapshot.copy(), lastSnapshot.getCount(), 0, Action.PERFORM);
        }
        if (!incoming.isEmpty()) {
            disk.manualInsert(slotIndex, incoming.copy(), incoming.getCount(), Action.PERFORM);
        }
        lastSnapshot = incoming.copy();
        super.set(incoming);
    }

    @Override
    public void setChanged() {
        super.setChanged();
        ItemStack current = this.container.getItem(this.getContainerSlot());

        if (ItemStack.matches(lastSnapshot, current)) {
            int delta = current.getCount() - lastSnapshot.getCount();
            if (delta > 0) {
                RSIntegrationMod.LOGGER.info("[RSI-Slot] setChanged() slot={} +{}: {} -> {} diskStored={}",
                        slotIndex, delta,
                        lastSnapshot.getDisplayName().getString() + " x" + lastSnapshot.getCount(),
                        current.getDisplayName().getString() + " x" + current.getCount(),
                        disk.getStored());
                ItemStack ins = current.copy();
                ins.setCount(delta);
                disk.manualInsert(slotIndex, ins, delta, Action.PERFORM);
            } else if (delta < 0) {
                RSIntegrationMod.LOGGER.info("[RSI-Slot] setChanged() slot={} {}: {} -> {} diskStored={}",
                        slotIndex, delta,
                        lastSnapshot.getDisplayName().getString() + " x" + lastSnapshot.getCount(),
                        current.getDisplayName().getString() + " x" + current.getCount(),
                        disk.getStored());
                ItemStack ext = lastSnapshot.copy();
                ext.setCount(-delta);
                disk.manualExtract(slotIndex, ext, -delta, 0, Action.PERFORM);
            }
        } else {
            RSIntegrationMod.LOGGER.info("[RSI-Slot] setChanged() slot={} item changed: {} -> {} diskStored={}",
                    slotIndex,
                    lastSnapshot.isEmpty() ? "EMPTY" : lastSnapshot.getDisplayName().getString() + " x" + lastSnapshot.getCount(),
                    current.isEmpty() ? "EMPTY" : current.getDisplayName().getString() + " x" + current.getCount(),
                    disk.getStored());
            if (!lastSnapshot.isEmpty())
                disk.manualExtract(slotIndex, lastSnapshot.copy(), lastSnapshot.getCount(), 0, Action.PERFORM);
            if (!current.isEmpty())
                disk.manualInsert(slotIndex, current.copy(), current.getCount(), Action.PERFORM);
        }
        lastSnapshot = current.copy();
    }
}
