package com.huanghuang.rsintegration.resonance.backpack;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.resonance.api.ResonanceStackRules;
import com.huanghuang.rsintegration.resonance.api.ResonanceStorageView;
import com.huanghuang.rsintegration.resonance.passive.PassiveEffectEngine;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.nbt.CompoundTag;

public class ResonanceDiskInventory implements Container {

    static final int SLOTS = 36;
    private static final String RSI_SLOT_TAG = "RSISlot";

    private final ResonanceStorageView disk;
    private final ServerPlayer owner;
    private final ItemStack[] slots = new ItemStack[SLOTS];
    private final ItemStack[] committed = new ItemStack[SLOTS];
    private final int[] backingSlots = new int[SLOTS];
    private boolean reconciling;
    private boolean reloadedAfterRecoveryFailure;
    private long lastSeenRevision;

    public ResonanceDiskInventory(ResonanceStorageView disk, ServerPlayer owner) {
        this.disk = disk;
        this.owner = owner;
        for (int i = 0; i < SLOTS; i++) {
            slots[i] = ItemStack.EMPTY;
            committed[i] = ItemStack.EMPTY;
            backingSlots[i] = i;
        }
        loadFromDisk();
        lastSeenRevision = disk.contentRevision();
    }

    private void loadFromDisk() {
        int loaded = 0;
        int migrated = 0;
        int split = disk.normalizeForMenu();
        List<ItemStack> storedStacks = new ArrayList<>();
        for (ResonanceStorageView.StoredStack entry : disk.storedStacks()) {
            ItemStack stored = entry.stack().copy();
            if (stored.isEmpty()) continue;
            ItemStack display = stored.copy();
            CompoundTag tag = display.getTag();
            int designated = entry.slot();
            if ((designated < 0 || designated >= SLOTS)
                    && tag != null && tag.contains(RSI_SLOT_TAG)) {
                designated = tag.getInt(RSI_SLOT_TAG);
            }
            stripSlotTag(display);

            int slot = designated;
            boolean remapped = slot < 0 || slot >= SLOTS || !slots[slot].isEmpty();
            if (remapped) {
                slot = nextEmpty();
                migrated++;
            }
            if (slot < 0) {
                RSIntegrationMod.LOGGER.warn("[RSI-Backpack] {} distinct stacks exceed {} UI slots; "
                        + "remaining stacks stay accessible through the storage network",
                        disk.storedStacks().size(), SLOTS);
                break;
            }
            int backingSlot = designated >= 0 && designated < SLOTS && !remapped ? designated : slot;
            if (backingSlot != designated && !disk.remapLogicalSlot(stored, backingSlot)) {
                // Keep the original identity if migration was rejected; exact variant
                // matching still makes the current session safe to use.
                backingSlot = designated >= 0 ? designated : slot;
            }
            backingSlots[slot] = backingSlot;
            slots[slot] = display.copy();
            committed[slot] = display.copy();
            loaded++;
        }
        if (loaded > 0) {
            RSIntegrationMod.LOGGER.info("[RSI-Backpack] Loaded {} stacks ({} remapped, {} non-stackable items split), diskStored={}",
                    loaded, migrated, split, disk.getStored());
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
        int removedCount = ResonanceStackRules.isLogicallyNonStackable(previous) ? 1
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
        int limit = ResonanceStackRules.isLogicallyNonStackable(requested) ? 1
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
        if (!current.isEmpty() && !ResonanceStackRules.isSameVariant(current, sanitize(stack))) return 0;
        // Non-stackable items (e.g. SlashBlade) must occupy one logical slot each.
        if (!current.isEmpty() && ResonanceStackRules.isLogicallyNonStackable(stack)) return 0;
        int stackSpace = (ResonanceStackRules.isLogicallyNonStackable(stack) ? 1 : stack.getMaxStackSize())
                - current.getCount();
        int capacitySpace = disk.getCapacity() - disk.getStored();
        int request = Math.min(stack.getCount(), Math.min(stackSpace, capacitySpace));
        ItemStack remainder = disk.insertView(index, stack, Math.max(0, request), true);
        return Math.max(0, request - remainder.getCount());
    }

    int getStoredCount() { return disk.getStored(); }

    int getCapacity() { return disk.getCapacity(); }

    private boolean commit(int index, ItemStack previous, ItemStack requested) {
        reloadedAfterRecoveryFailure = false;
        if (sameStack(previous, requested)) return true;
        ResonanceStorageView.SlotMutationResult result =
                disk.reconcileSlotView(backingSlots[index], previous, requested);
        if (result == ResonanceStorageView.SlotMutationResult.RECOVERY_FAILED) {
            RSIntegrationMod.LOGGER.error("[RSI-Backpack] Slot {} mutation and recovery failed: {} x{} -> {} x{}; reloading disk state",
                    index, previous.getItem(), previous.getCount(), requested.getItem(), requested.getCount());
            reloadFromDisk();
            reloadedAfterRecoveryFailure = true;
        } else if (result == ResonanceStorageView.SlotMutationResult.REJECTED) {
            RSIntegrationMod.LOGGER.warn("[RSI-Backpack] Rejected slot {} mutation: {} x{} -> {} x{}",
                    index, previous.getItem(), previous.getCount(), requested.getItem(), requested.getCount());
        }
        boolean success = result == ResonanceStorageView.SlotMutationResult.SUCCESS;
        if (success && owner != null) PassiveEffectEngine.refreshPlayer(owner);
        return success;
    }

    private void reloadFromDisk() {
        for (int i = 0; i < SLOTS; i++) {
            slots[i] = ItemStack.EMPTY;
            committed[i] = ItemStack.EMPTY;
            backingSlots[i] = i;
        }
        loadFromDisk();
        lastSeenRevision = disk.contentRevision();
    }

    /**
     * Refresh the open backpack view after another integration path mutates the
     * same disk. The disk revision is monotonic and changes only after a real
     * mutation, so this check is cheap enough to run for every menu broadcast.
     */
    boolean reloadIfRevisionChanged() {
        if (disk.contentRevision() == lastSeenRevision) return false;
        reloadFromDisk();
        return true;
    }

    private static ItemStack sanitize(ItemStack stack) {
        if (stack.isEmpty()) return ItemStack.EMPTY;
        ItemStack copy = stack.copy();
        stripSlotTag(copy);
        return copy;
    }

    private static boolean sameStack(ItemStack first, ItemStack second) {
        if (first.isEmpty() || second.isEmpty()) return first.isEmpty() && second.isEmpty();
        return first.getCount() == second.getCount()
                && ResonanceStackRules.isSameVariant(first, second);
    }

    private static void stripSlotTag(ItemStack stack) {
        if (stack.hasTag()) {
            stack.getTag().remove(RSI_SLOT_TAG);
            if (stack.getTag().isEmpty()) stack.setTag(null);
        }
    }
}
