package com.huanghuang.rsintegration.resonance.backpack;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.resonance.disk.ResonanceDiskWrapper;
import com.refinedmods.refinedstorage.api.util.Action;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

public class ResonanceDiskInventory implements Container {

    static final int SLOTS = 36;
    private static final String RSI_SLOT_TAG = "RSISlot";

    private final ResonanceDiskWrapper disk;
    private final ItemStack[] slots = new ItemStack[SLOTS];

    public ResonanceDiskInventory(ResonanceDiskWrapper disk) {
        this.disk = disk;
        for (int i = 0; i < SLOTS; i++) slots[i] = ItemStack.EMPTY;
        loadFromDisk();
    }

    private void loadFromDisk() {
        int loaded = 0;
        int migrated = 0;
        // First pass: items with RSISlot tag → designated slot (KEEP tag for NBT match with disk)
        for (ItemStack stack : disk.delegate().getStacks()) {
            if (stack.isEmpty()) continue;
            net.minecraft.nbt.CompoundTag tag = stack.getTag();
            int slot = (tag != null && tag.contains(RSI_SLOT_TAG)) ? tag.getInt(RSI_SLOT_TAG) : -1;
            if (slot < 0 || slot >= SLOTS) continue;
            slots[slot] = stack.copy();
            loaded++;
        }
        // Second pass: items without a valid slot assignment → fill empty slots.
        // Covers: (a) pre-slot-tracking items with no RSISlot tag,
        //         (b) items inserted via RSInventoryBridge/TickSimulator with RSISlot=-1.
        // Container and disk copies share the same NBT, so manualExtract can find them.
        // On first player interaction, ResonanceSlot.set() will re-tag them via manualInsert.
        for (ItemStack stack : disk.delegate().getStacks()) {
            if (stack.isEmpty()) continue;
            net.minecraft.nbt.CompoundTag tag = stack.getTag();
            int slotVal = (tag != null && tag.contains(RSI_SLOT_TAG)) ? tag.getInt(RSI_SLOT_TAG) : -1;
            if (slotVal >= 0 && slotVal < SLOTS) continue; // already placed by pass 1
            int slot = nextEmpty();
            if (slot < 0) break;
            slots[slot] = stack.copy();
            loaded++;
            migrated++;
        }
        if (loaded > 0) RSIntegrationMod.LOGGER.info("[RSI-Backpack] loadFromDisk done: {} loaded ({} migrated), diskStored={}",
                loaded, migrated, disk.getStored());
    }

    private int nextEmpty() {
        for (int i = 0; i < SLOTS; i++)
            if (slots[i].isEmpty()) return i;
        return -1;
    }

    @Override
    public int getContainerSize() { return SLOTS; }

    @Override
    public boolean isEmpty() {
        for (ItemStack s : slots) if (!s.isEmpty()) return false;
        return true;
    }

    @Override
    public ItemStack getItem(int index) { return slots[index]; }

    @Override
    public ItemStack removeItem(int index, int count) {
        ItemStack stack = slots[index];
        if (stack.isEmpty()) return ItemStack.EMPTY;
        ItemStack taken = stack.split(count);
        if (stack.isEmpty()) slots[index] = ItemStack.EMPTY;
        // Disk sync handled by ResonanceSlot.setChanged() — always called after removeItem.
        ResonanceDiskWrapper.rsi$stripSlotTag(taken);
        return taken;
    }

    @Override
    public ItemStack removeItemNoUpdate(int index) {
        ItemStack stack = slots[index];
        if (stack.isEmpty()) return ItemStack.EMPTY;
        slots[index] = ItemStack.EMPTY;
        disk.manualExtract(index, stack.copy(), stack.getCount(), 0, Action.PERFORM);
        ResonanceDiskWrapper.rsi$stripSlotTag(stack);
        return stack;
    }

    @Override
    public void setItem(int index, ItemStack stack) {
        slots[index] = stack;
    }

    @Override
    public int getMaxStackSize() { return 64; }

    @Override
    public void setChanged() {}

    @Override
    public boolean stillValid(Player player) { return true; }

    @Override
    public void clearContent() {
        for (int i = 0; i < SLOTS; i++) {
            if (!slots[i].isEmpty()) {
                disk.manualExtract(i, slots[i].copy(), slots[i].getCount(), 0, Action.PERFORM);
            }
            slots[i] = ItemStack.EMPTY;
        }
    }

    int getStoredCount() { return disk.getStored(); }

    int getCapacity() { return disk.getCapacity(); }
}
