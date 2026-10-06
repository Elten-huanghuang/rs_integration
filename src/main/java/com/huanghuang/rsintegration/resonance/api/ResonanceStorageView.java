package com.huanghuang.rsintegration.resonance.api;

import net.minecraft.world.item.ItemStack;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.function.Predicate;

/**
 * Backend-neutral view of a resonance disk.
 *
 * <p>The view deliberately contains only resonance semantics.  It does not
 * expose Refined Storage or BeyondDimensions types, so passive effects and
 * compatibility integrations can be shared by both backends.</p>
 */
public interface ResonanceStorageView {

    /** Stable backend identifier used for diagnostics and deterministic routing. */
    String backendId();

    /** Detached snapshot of the stacks and their logical slots. */
    List<StoredStack> storedStacks();

    /** 谓词只接触独立副本；后端可复用私有快照并提前结束查询。 */
    default boolean hasItem(Predicate<ItemStack> predicate) {
        for (StoredStack stored : storedStacks()) {
            if (predicate.test(stored.stack())) return true;
        }
        return false;
    }

    default int countItems(Predicate<ItemStack> predicate) {
        int count = 0;
        for (StoredStack stored : storedStacks()) {
            ItemStack stack = stored.stack();
            if (predicate.test(stack)) count += stack.getCount();
        }
        return count;
    }

    int getStored();

    int getCapacity();

    int abilityMask();

    boolean hasAbility(int ability);

    boolean unlockAbility(int ability);

    /** Persists an ability mutation; optional backends may flush lazily. */
    default void markDirty(ServerPlayer player) {}

    /** Monotonic content revision used to invalidate passive-effect snapshots. */
    long contentRevision();

    /** Performs backend-specific normalization before the menu snapshots slots. */
    default int normalizeForMenu() { return 0; }

    /** Persists a logical-slot reassignment when the menu repairs old layout data. */
    default boolean remapLogicalSlot(ItemStack stack, int logicalSlot) { return true; }

    /**
     * Reconciles one logical slot atomically.  Both arguments are untagged,
     * detached stacks; backend-specific slot markers must stay inside the
     * adapter.
     */
    SlotMutationResult reconcileSlotView(int slot, ItemStack previous, ItemStack requested);

    /** Exact extraction, optionally simulated. */
    ItemStack extractExactView(int slot, ItemStack template, int size, boolean simulate);

    /** Exact insertion into a logical slot, optionally simulated. */
    ItemStack insertView(int slot, ItemStack stack, int size, boolean simulate);

    /** Extracts an unassigned stack for integrations that do not own a slot. */
    default ItemStack extractUnassigned(ItemStack template, int size, boolean simulate) {
        return extractExactView(-1, template, size, simulate);
    }

    /** Inserts an unassigned stack for integrations that do not own a slot. */
    default ItemStack insertUnassigned(ItemStack stack, int size, boolean simulate) {
        return insertView(-1, stack, size, simulate);
    }

    enum SlotMutationResult {
        SUCCESS,
        REJECTED,
        RECOVERY_FAILED
    }

    /** A detached stack snapshot with its logical slot identity. */
    record StoredStack(int slot, ItemStack stack) {
        public StoredStack {
            stack = stack.copy();
        }
    }
}
