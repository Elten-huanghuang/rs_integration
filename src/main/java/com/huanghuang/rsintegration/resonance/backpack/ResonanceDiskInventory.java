package com.huanghuang.rsintegration.resonance.backpack;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.resonance.disk.ResonanceDiskWrapper;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

public class ResonanceDiskInventory implements Container {

    static final int SLOTS = 36;
    private static final String RSI_SLOT_TAG = "RSISlot";

    private final ResonanceDiskWrapper disk;
    private final ItemStack[] slots = new ItemStack[SLOTS];
    private final ItemStack[] committed = new ItemStack[SLOTS];
    private final int[] backingSlots = new int[SLOTS];
    private boolean reconciling;
    private boolean reloadedAfterRecoveryFailure;

    public ResonanceDiskInventory(ResonanceDiskWrapper disk) {
        this.disk = disk;
        for (int i = 0; i < SLOTS; i++) {
            slots[i] = ItemStack.EMPTY;
            committed[i] = ItemStack.EMPTY;
            backingSlots[i] = i;
        }
        loadFromDisk();
    }

    private void loadFromDisk() {
        int loaded = 0;
        int migrated = 0;
        for (ItemStack stored : disk.delegate().getStacks()) {
            if (stored.isEmpty()) continue;
            ItemStack display = stored.copy();
            net.minecraft.nbt.CompoundTag tag = display.getTag();
            int designated = tag != null && tag.contains(RSI_SLOT_TAG)
                    ? tag.getInt(RSI_SLOT_TAG) : -1;
            ResonanceDiskWrapper.rsi$stripSlotTag(display);

            int slot = designated;
            boolean remapped = slot < 0 || slot >= SLOTS || !slots[slot].isEmpty();
            if (remapped) {
                slot = nextEmpty();
                migrated++;
            }
            if (slot < 0) {
                RSIntegrationMod.LOGGER.warn("[RSI-Backpack] {} distinct stacks exceed {} UI slots; "
                        + "remaining stacks stay accessible through the RS grid",
                        disk.delegate().getStacks().size(), SLOTS);
                break;
            }
            backingSlots[slot] = designated >= 0 && designated < SLOTS ? designated : slot;
            slots[slot] = display.copy();
            committed[slot] = display.copy();
            loaded++;
        }
        if (loaded > 0) {
            RSIntegrationMod.LOGGER.info("[RSI-Backpack] Loaded {} stacks ({} remapped), diskStored={}",
                    loaded, migrated, disk.getStored());
        }
    }

    private int nextEmpty() {
        for (int i = 0; i < SLOTS; i++) {
            if (slots[i].isEmpty()) return i;
        }
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
        if (count <= 0 || slots[index].isEmpty()) return ItemStack.EMPTY;
        ItemStack previous = committed[index].copy();
        int removedCount = ResonanceDiskWrapper.isLogicallyNonStackable(previous) ? 1
                : Math.min(count, previous.getCount());
        ItemStack requested = previous.copy();
        requested.shrink(removedCount);
        if (requested.isEmpty()) requested = ItemStack.EMPTY;
        if (!commit(index, previous, requested)) return ItemStack.EMPTY;

        ItemStack taken = previous.copyWithCount(removedCount);
        if (!reloadedAfterRecoveryFailure) {
            slots[index] = requested.copy();
            committed[index] = requested.copy();
        }
        return taken;
    }

    @Override
    public ItemStack removeItemNoUpdate(int index) {
        ItemStack previous = committed[index].copy();
        if (previous.isEmpty() || !commit(index, previous, ItemStack.EMPTY)) return ItemStack.EMPTY;
        slots[index] = ItemStack.EMPTY;
        committed[index] = ItemStack.EMPTY;
        return previous;
    }

    @Override
    public void setItem(int index, ItemStack stack) {
        ItemStack requested = sanitize(stack);
        int limit = ResonanceDiskWrapper.isLogicallyNonStackable(requested) ? 1
                : Math.min(getMaxStackSize(), requested.getMaxStackSize());
        if (!requested.isEmpty() && requested.getCount() > limit) requested.setCount(limit);

        ItemStack previous = committed[index].copy();
        if (commit(index, previous, requested)) {
            slots[index] = requested.copy();
            committed[index] = requested.copy();
        } else if (!reloadedAfterRecoveryFailure) {
            slots[index] = previous.copy();
        }
    }

    @Override
    public int getMaxStackSize() { return 64; }

    @Override
    public void setChanged() {
        if (reconciling) return;
        reconciling = true;
        try {
            for (int i = 0; i < SLOTS; i++) {
                ItemStack requested = sanitize(slots[i]);
                ItemStack previous = committed[i].copy();
                if (sameStack(previous, requested)) continue;
                if (commit(i, previous, requested)) {
                    slots[i] = requested.copy();
                    committed[i] = requested.copy();
                } else if (!reloadedAfterRecoveryFailure) {
                    slots[i] = previous.copy();
                } else {
                    break;
                }
            }
        } finally {
            reconciling = false;
        }
    }

    @Override
    public boolean stillValid(Player player) { return true; }

    @Override
    public void clearContent() {
        for (int i = 0; i < SLOTS; i++) {
            ItemStack previous = committed[i].copy();
            if (previous.isEmpty()) continue;
            if (commit(i, previous, ItemStack.EMPTY)) {
                slots[i] = ItemStack.EMPTY;
                committed[i] = ItemStack.EMPTY;
            backingSlots[i] = i;
            }
        }
    }

    int simulateAccept(int index, ItemStack stack) {
        if (stack.isEmpty()) return 0;
        ItemStack current = committed[index];
        if (!current.isEmpty() && !ResonanceDiskWrapper.isSameVariant(current, sanitize(stack))) return 0;
        // Non-stackable items (e.g. SlashBlade) must occupy one logical slot each.
        if (!current.isEmpty() && ResonanceDiskWrapper.isLogicallyNonStackable(stack)) return 0;
        int stackSpace = (ResonanceDiskWrapper.isLogicallyNonStackable(stack) ? 1 : stack.getMaxStackSize())
                - current.getCount();
        int capacitySpace = disk.getCapacity() - disk.getStored();
        int request = Math.min(stack.getCount(), Math.min(stackSpace, capacitySpace));
        return disk.simulateInsertCount(index, stack, Math.max(0, request));
    }

    int getStoredCount() { return disk.getStored(); }

    int getCapacity() { return disk.getCapacity(); }

    private boolean commit(int index, ItemStack previous, ItemStack requested) {
        reloadedAfterRecoveryFailure = false;
        if (sameStack(previous, requested)) return true;
        ResonanceDiskWrapper.SlotMutationResult result =
                disk.reconcileSlot(backingSlots[index], previous, requested);
        if (result == ResonanceDiskWrapper.SlotMutationResult.RECOVERY_FAILED) {
            RSIntegrationMod.LOGGER.error("[RSI-Backpack] Slot {} mutation and recovery failed: {} x{} -> {} x{}; reloading disk state",
                    index, previous.getItem(), previous.getCount(), requested.getItem(), requested.getCount());
            reloadFromDisk();
            reloadedAfterRecoveryFailure = true;
        } else if (result == ResonanceDiskWrapper.SlotMutationResult.REJECTED) {
            RSIntegrationMod.LOGGER.warn("[RSI-Backpack] Rejected slot {} mutation: {} x{} -> {} x{}",
                    index, previous.getItem(), previous.getCount(), requested.getItem(), requested.getCount());
        }
        return result == ResonanceDiskWrapper.SlotMutationResult.SUCCESS;
    }

    private void reloadFromDisk() {
        for (int i = 0; i < SLOTS; i++) {
            slots[i] = ItemStack.EMPTY;
            committed[i] = ItemStack.EMPTY;
            backingSlots[i] = i;
        }
        loadFromDisk();
    }

    private static ItemStack sanitize(ItemStack stack) {
        if (stack.isEmpty()) return ItemStack.EMPTY;
        ItemStack copy = stack.copy();
        ResonanceDiskWrapper.rsi$stripSlotTag(copy);
        return copy;
    }

    private static boolean sameStack(ItemStack first, ItemStack second) {
        if (first.isEmpty() || second.isEmpty()) return first.isEmpty() && second.isEmpty();
        return first.getCount() == second.getCount()
                && ResonanceDiskWrapper.isSameVariant(first, second);
    }
}
