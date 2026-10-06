package com.huanghuang.rsintegration.resonance.disk;

import com.huanghuang.rsintegration.resonance.api.ResonanceStorageView;
import com.huanghuang.rsintegration.resonance.api.ResonanceStackRules;
import com.refinedmods.refinedstorage.api.storage.AccessType;
import com.refinedmods.refinedstorage.api.storage.disk.IStorageDisk;
import com.refinedmods.refinedstorage.api.storage.disk.IStorageDiskContainerContext;
import com.refinedmods.refinedstorage.api.storage.disk.IStorageDiskListener;
import com.refinedmods.refinedstorage.api.util.Action;
import com.refinedmods.refinedstorage.api.util.IComparer;
import com.refinedmods.refinedstorage.apiimpl.API;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

public final class ResonanceDiskWrapper implements IStorageDisk<ItemStack>, ResonanceStorageView {

    public static final ResourceLocation FACTORY_ID =
            new ResourceLocation("rs_integration", "resonance");

    private static final String RSI_SLOT_TAG = "RSISlot";

    private final IStorageDisk<ItemStack> delegate;
    private int abilityMask;
    private int mutationDepth;
    private boolean mutationDirty;
    private long contentRevision;
    private QuerySnapshot querySnapshot;

    public ResonanceDiskWrapper(IStorageDisk<ItemStack> delegate) {
        this(delegate, 0);
    }

    public ResonanceDiskWrapper(IStorageDisk<ItemStack> delegate, int abilityMask) {
        this.delegate = delegate;
        this.abilityMask = abilityMask;
    }

    public static boolean isLogicallyNonStackable(ItemStack stack) {
        return ResonanceStackRules.isLogicallyNonStackable(stack);
    }

    /** Item identity used by logical backpack slots; NBT variants must never merge. */
    public static boolean isSameVariant(ItemStack first, ItemStack second) {
        return ResonanceStackRules.isSameVariant(first, second);
    }
    public IStorageDisk<ItemStack> delegate() {
        return delegate;
    }

    @Override
    public String backendId() {
        return "refinedstorage";
    }

    public int abilityMask() {
        return abilityMask;
    }

    public long contentRevision() {
        return contentRevision;
    }

    @Override
    public int normalizeForMenu() {
        return splitLogicallyNonStackableStacks();
    }

    @Override
    public boolean remapLogicalSlot(ItemStack stack, int logicalSlot) {
        return moveSlot(stack, logicalSlot);
    }

    @Override
    public List<ResonanceStorageView.StoredStack> storedStacks() {
        List<ResonanceStorageView.StoredStack> result = new ArrayList<>();
        for (ItemStack stored : delegate.getStacks()) {
            if (stored.isEmpty()) continue;
            CompoundTag tag = stored.getTag();
            int slot = tag != null && tag.contains(RSI_SLOT_TAG)
                    ? tag.getInt(RSI_SLOT_TAG) : -1;
            ResonanceStorageView.StoredStack detached =
                    new ResonanceStorageView.StoredStack(slot, stored);
            rsi$stripSlotTag(detached.stack());
            result.add(detached);
        }
        return List.copyOf(result);
    }

    @Override
    public boolean hasItem(Predicate<ItemStack> predicate) {
        for (ResonanceStorageView.StoredStack stored : queryStacks()) {
            if (predicate.test(stored.stack().copy())) return true;
        }
        return false;
    }

    @Override
    public int countItems(Predicate<ItemStack> predicate) {
        int count = 0;
        for (ResonanceStorageView.StoredStack stored : queryStacks()) {
            ItemStack stack = stored.stack().copy();
            if (predicate.test(stack)) count += stack.getCount();
        }
        return count;
    }

    private List<ResonanceStorageView.StoredStack> queryStacks() {
        // 事务内的内容版本尚未递增，不能缓存中间状态。
        if (mutationDirty) return storedStacks();
        if (querySnapshot == null || querySnapshot.revision() != contentRevision) {
            querySnapshot = new QuerySnapshot(contentRevision, storedStacks());
        }
        return querySnapshot.stacks();
    }

    private record QuerySnapshot(long revision, List<ResonanceStorageView.StoredStack> stacks) {}

    public boolean hasAbility(int ability) {
        return (abilityMask & ability) == ability;
    }

    public boolean unlockAbility(int ability) {
        int updated = abilityMask | ability;
        if (updated == abilityMask) return false;
        abilityMask = updated;
        return true;
    }

    @Override
    public void markDirty(ServerPlayer player) {
        if (player == null) return;
        for (var level : player.server.getAllLevels()) {
            API.instance().getStorageDiskManager(level).markForSaving();
        }
    }

    @Override
    public ItemStack insert(ItemStack stack, int size, Action action) {
        return stack;
    }

    public ItemStack manualInsert(int slot, ItemStack stack, int size, Action action) {
        ItemStack tagged = stack.copy();
        tagged.getOrCreateTag().putInt(RSI_SLOT_TAG, slot);
        int storedBefore = delegate.getStored();
        ItemStack remainder = delegate.insert(tagged, size, action);
        if (action == Action.PERFORM && delegate.getStored() != storedBefore) markInternalMutation();
        return remainder;
    }

    /** Inserts an internal stack without assigning a logical backpack slot yet. */
    public ItemStack manualInsertUnassigned(ItemStack stack, int size, Action action) {
        int storedBefore = delegate.getStored();
        ItemStack remainder = delegate.insert(stack.copy(), size, action);
        if (action == Action.PERFORM && delegate.getStored() != storedBefore) markInternalMutation();
        return remainder;
    }

    @Override
    public ResourceLocation getFactoryId() {
        return FACTORY_ID;
    }

    @Override
    public Collection<ItemStack> getStacks() {
        // Resonance slots are private capability/passive state, not ordinary RS
        // storage. Exposing them here makes the RS cache advertise items that
        // insert/extract intentionally reject, producing unusable materials and
        // stale grid entries after a backpack mutation.
        return List.of();
    }

    /** Snapshot for explicitly integrated resonance features only. */
    public List<ItemStack> getInternalStacks() {
        List<ItemStack> raw = new ArrayList<>();
        for (ItemStack s : delegate.getStacks()) raw.add(s.copy());
        raw.sort(Comparator.comparingInt(s ->
                s.getTag() != null ? s.getTag().getInt(RSI_SLOT_TAG) : Integer.MAX_VALUE));
        for (ItemStack s : raw) rsi$stripSlotTag(s);
        return List.copyOf(raw);
    }

    @Override
    public ItemStack extract(ItemStack stack, int size, int flags, Action action) {
        return ItemStack.EMPTY;
    }

    public ItemStack manualExtract(int slot, ItemStack template, int size, int flags, Action action) {
        ItemStack tagged = template.copy();
        tagged.getOrCreateTag().putInt(RSI_SLOT_TAG, slot);
        for (ItemStack stored : delegate.getStacks()) {
            CompoundTag tag = stored.getTag();
            if (tag != null && tag.contains(RSI_SLOT_TAG) && tag.getInt(RSI_SLOT_TAG) == slot) {
                ItemStack probe = stored.copy();
                rsi$stripSlotTag(probe);
                if (isSameVariant(probe, template)) { tagged = stored.copy(); break; }
            }
        }
        // Slot-tagged stacks are still vulnerable to RS's default item-only
        // comparison when callers pass flags=0.  A disk can contain several
        // variants of the same item (e.g. Apotheosis potion charms), so an
        // extraction must include the complete NBT identity or RS may debit a
        // different variant and the slot mutation will be rejected.
        ItemStack result = delegate.extract(tagged, size, flags | IComparer.COMPARE_NBT, action);
        if (action == Action.PERFORM && !result.isEmpty()) markInternalMutation();
        if (!result.isEmpty()) rsi$stripSlotTag(result);
        return result;
    }

    /** Moves one exact stored variant to a different logical slot identity. */
    public boolean moveSlot(ItemStack exactStoredStack, int newSlot) {
        beginMutation();
        try {
            if (exactStoredStack.isEmpty()) return false;
            ItemStack simulated = delegate.extract(
                    exactStoredStack, exactStoredStack.getCount(), 0, Action.SIMULATE);
            if (!sameExactTaggedStack(exactStoredStack, simulated)) return false;
            ItemStack removed = delegate.extract(exactStoredStack, exactStoredStack.getCount(), 0, Action.PERFORM);
            if (!removed.isEmpty()) markInternalMutation();
            if (!sameExactTaggedStack(exactStoredStack, removed)) {
                if (!removed.isEmpty()) delegate.insert(removed, removed.getCount(), Action.PERFORM);
                return false;
            }
            removed.getOrCreateTag().putInt(RSI_SLOT_TAG, newSlot);
            ItemStack remainder = delegate.insert(removed, removed.getCount(), Action.PERFORM);
            if (remainder.isEmpty()) return true;

            // Best-effort rollback under the original exact identity.
            int inserted = removed.getCount() - remainder.getCount();
            if (inserted > 0) delegate.extract(removed, inserted, 0, Action.PERFORM);
            delegate.insert(exactStoredStack, exactStoredStack.getCount(), Action.PERFORM);
            return false;
        } finally {
            endMutation();
        }
    }

    /** Repairs legacy stacks of items that must occupy one logical slot per item. */
    public int splitLogicallyNonStackableStacks() {
        beginMutation();
        try {
            List<ItemStack> snapshot = new ArrayList<>();
            Set<Integer> usedSlots = new HashSet<>();
            for (ItemStack stored : delegate.getStacks()) {
                ItemStack exact = stored.copy();
                snapshot.add(exact);
                CompoundTag tag = exact.getTag();
                if (tag != null && tag.contains(RSI_SLOT_TAG)) {
                    usedSlots.add(tag.getInt(RSI_SLOT_TAG));
                }
            }

            int split = 0;
            int nextSlot = 0;
            for (ItemStack exact : snapshot) {
                if (exact.getCount() <= 1 || !isLogicallyNonStackable(exact)) continue;
                ItemStack removed = delegate.extract(exact, exact.getCount(), 0, Action.PERFORM);
                if (!removed.isEmpty()) markInternalMutation();
                if (removed.getCount() != exact.getCount()) {
                    if (!removed.isEmpty()) {
                        delegate.insert(removed, removed.getCount(), Action.PERFORM);
                    }
                    continue;
                }

                for (int i = 0; i < removed.getCount(); i++) {
                    while (usedSlots.contains(nextSlot)) nextSlot++;
                    ItemStack single = removed.copyWithCount(1);
                    single.getOrCreateTag().putInt(RSI_SLOT_TAG, nextSlot);
                    ItemStack remainder = delegate.insert(single, 1, Action.PERFORM);
                    if (remainder.isEmpty()) {
                        usedSlots.add(nextSlot++);
                    } else {
                        // Preserve the item even if the delegate unexpectedly rejects
                        // the repaired identity.
                        ItemStack fallback = exact.copyWithCount(1);
                        delegate.insert(fallback, 1, Action.PERFORM);
                    }
                }
                split += removed.getCount() - 1;
            }
            return split;
        } finally {
            endMutation();
        }
    }

    public ItemStack manualExtractExact(ItemStack exactTaggedStack, int size, int flags, Action action) {
        ItemStack result = delegate.extract(exactTaggedStack, size, flags | IComparer.COMPARE_NBT, action);
        if (action == Action.PERFORM && !result.isEmpty()) markInternalMutation();
        if (!result.isEmpty()) rsi$stripSlotTag(result);
        return result;
    }

    @Override
    public ItemStack extractExactView(int slot, ItemStack template, int size, boolean simulate) {
        if (template.isEmpty() || size <= 0) return ItemStack.EMPTY;
        return slot < 0
                ? manualExtractUnassignedExact(template, size,
                        simulate ? Action.SIMULATE : Action.PERFORM)
                : manualExtract(slot, template, size, 0,
                        simulate ? Action.SIMULATE : Action.PERFORM);
    }

    @Override
    public ItemStack insertView(int slot, ItemStack stack, int size, boolean simulate) {
        if (stack.isEmpty() || size <= 0) return stack;
        Action action = simulate ? Action.SIMULATE : Action.PERFORM;
        return slot < 0
                ? manualInsertUnassigned(stack, size, action)
                : manualInsert(slot, stack, size, action);
    }

    private ItemStack manualExtractUnassignedExact(ItemStack template, int size, Action action) {
        ItemStack result = delegate.extract(template, size,
                IComparer.COMPARE_NBT, action);
        if (action == Action.PERFORM && !result.isEmpty()) markInternalMutation();
        if (!result.isEmpty()) rsi$stripSlotTag(result);
        return result;
    }

    @Override
    public ResonanceStorageView.SlotMutationResult reconcileSlotView(
            int slot, ItemStack previous, ItemStack requested) {
        return switch (reconcileSlot(slot, previous, requested)) {
            case SUCCESS -> ResonanceStorageView.SlotMutationResult.SUCCESS;
            case REJECTED -> ResonanceStorageView.SlotMutationResult.REJECTED;
            case RECOVERY_FAILED -> ResonanceStorageView.SlotMutationResult.RECOVERY_FAILED;
        };
    }

    public enum SlotMutationResult {
        SUCCESS,
        REJECTED,
        RECOVERY_FAILED
    }

    /** Atomically reconcile one logical backpack slot against the disk delegate. */
    public SlotMutationResult reconcileSlot(int slot, ItemStack previous, ItemStack requested) {
        beginMutation();
        try {
            ItemStack oldStack = sanitized(previous);
            ItemStack newStack = sanitized(requested);
            if (sameStack(oldStack, newStack)) return SlotMutationResult.SUCCESS;
            if (!newStack.isEmpty() && newStack.getCount()
                    > (isLogicallyNonStackable(newStack) ? 1 : newStack.getMaxStackSize())) {
                return SlotMutationResult.REJECTED;
            }

            if (!oldStack.isEmpty() && !newStack.isEmpty()
                    && isSameVariant(oldStack, newStack)) {
                int delta = newStack.getCount() - oldStack.getCount();
                return delta > 0
                        ? insertExact(slot, newStack, delta)
                        : extractExact(slot, oldStack, -delta);
            }

            if (!oldStack.isEmpty()) {
                ItemStack simulated = manualExtract(
                        slot, oldStack, oldStack.getCount(), 0, Action.SIMULATE);
                if (!sameExtractedVariant(oldStack, simulated)) {
                    return SlotMutationResult.REJECTED;
                }
            }
            if (!newStack.isEmpty()
                    && getCapacity() - getStored() + oldStack.getCount() < newStack.getCount()) {
                return SlotMutationResult.REJECTED;
            }

            ItemStack removed = ItemStack.EMPTY;
            if (!oldStack.isEmpty()) {
                removed = manualExtract(slot, oldStack, oldStack.getCount(), 0, Action.PERFORM);
                if (!sameExtractedVariant(oldStack, removed)) {
                    return restore(slot, removed)
                            ? SlotMutationResult.REJECTED : SlotMutationResult.RECOVERY_FAILED;
                }
            }

            if (newStack.isEmpty()) return SlotMutationResult.SUCCESS;
            ItemStack simulatedRemainder = manualInsert(
                    slot, newStack, newStack.getCount(), Action.SIMULATE);
            if (!simulatedRemainder.isEmpty()) {
                return restore(slot, removed)
                        ? SlotMutationResult.REJECTED : SlotMutationResult.RECOVERY_FAILED;
            }

            ItemStack remainder = manualInsert(
                    slot, newStack, newStack.getCount(), Action.PERFORM);
            if (remainder.isEmpty()) return SlotMutationResult.SUCCESS;

            int inserted = newStack.getCount() - remainder.getCount();
            boolean removedNew = inserted <= 0 || extractedExactly(slot, newStack, inserted);
            boolean restoredOld = restore(slot, removed);
            return removedNew && restoredOld
                    ? SlotMutationResult.REJECTED : SlotMutationResult.RECOVERY_FAILED;
        } finally {
            endMutation();
        }
    }

    public int simulateInsertCount(int slot, ItemStack stack, int size) {
        if (stack.isEmpty() || size <= 0) return 0;
        ItemStack remainder = manualInsert(slot, stack, size, Action.SIMULATE);
        return Math.max(0, size - remainder.getCount());
    }

    private SlotMutationResult insertExact(int slot, ItemStack template, int count) {
        if (count <= 0) return SlotMutationResult.SUCCESS;
        ItemStack simulated = manualInsert(slot, template, count, Action.SIMULATE);
        if (!simulated.isEmpty()) return SlotMutationResult.REJECTED;
        ItemStack remainder = manualInsert(slot, template, count, Action.PERFORM);
        if (remainder.isEmpty()) return SlotMutationResult.SUCCESS;
        int inserted = count - remainder.getCount();
        return inserted <= 0 || extractedExactly(slot, template, inserted)
                ? SlotMutationResult.REJECTED : SlotMutationResult.RECOVERY_FAILED;
    }

    private SlotMutationResult extractExact(int slot, ItemStack template, int count) {
        if (count <= 0) return SlotMutationResult.SUCCESS;
        ItemStack simulated = manualExtract(slot, template, count, 0, Action.SIMULATE);
        ItemStack expected = template.copyWithCount(count);
        if (!sameExtractedVariant(expected, simulated)) return SlotMutationResult.REJECTED;
        ItemStack extracted = manualExtract(slot, template, count, 0, Action.PERFORM);
        if (sameExtractedVariant(expected, extracted)) return SlotMutationResult.SUCCESS;
        return restore(slot, extracted)
                ? SlotMutationResult.REJECTED : SlotMutationResult.RECOVERY_FAILED;
    }

    private boolean extractedExactly(int slot, ItemStack template, int count) {
        ItemStack extracted = manualExtract(slot, template, count, 0, Action.PERFORM);
        return sameExtractedVariant(template.copyWithCount(count), extracted);
    }

    private boolean restore(int slot, ItemStack stack) {
        if (stack.isEmpty()) return true;
        ItemStack remainder = manualInsert(slot, stack, stack.getCount(), Action.PERFORM);
        return remainder.isEmpty();
    }

    private static ItemStack sanitized(ItemStack stack) {
        if (stack.isEmpty()) return ItemStack.EMPTY;
        ItemStack copy = stack.copy();
        rsi$stripSlotTag(copy);
        return copy;
    }

    private static boolean sameStack(ItemStack first, ItemStack second) {
        if (first.isEmpty() || second.isEmpty()) return first.isEmpty() && second.isEmpty();
        return first.getCount() == second.getCount() && isSameVariant(first, second);
    }

    private static boolean sameExtractedVariant(ItemStack expected, ItemStack extracted) {
        return expected.getCount() == extracted.getCount() && isSameVariant(expected, extracted);
    }

    private static boolean sameExactTaggedStack(ItemStack expected, ItemStack extracted) {
        return expected.getCount() == extracted.getCount()
                && ItemStack.isSameItemSameTags(expected, extracted);
    }

    @Override
    public int getStored() {
        return delegate.getStored();
    }

    @Override
    public int getPriority() {
        return delegate.getPriority();
    }

    @Override
    public AccessType getAccessType() {
        return delegate.getAccessType();
    }

    @Override
    public int getCacheDelta(int storedPreInsertion, int size, ItemStack remainder) {
        return delegate.getCacheDelta(storedPreInsertion, size, remainder);
    }

    @Override
    public int getCapacity() {
        return delegate.getCapacity();
    }

    @Override
    public UUID getOwner() {
        return delegate.getOwner();
    }

    @Override
    public void setSettings(IStorageDiskListener listener, IStorageDiskContainerContext context) {
        delegate.setSettings(listener, context);
    }

    private void beginMutation() {
        mutationDepth++;
    }

    private void endMutation() {
        if (--mutationDepth == 0 && mutationDirty) {
            mutationDirty = false;
            invalidateInternalView();
        }
    }

    private void markInternalMutation() {
        querySnapshot = null;
        mutationDirty = true;
        if (mutationDepth == 0) {
            mutationDirty = false;
            invalidateInternalView();
        }
    }

    private void invalidateInternalView() {
        contentRevision++;
        querySnapshot = null;
        // 普通 RS 库存始终看不到共鸣内容；只更新内部版本，避免终端全量重建。
    }

    @Override
    public CompoundTag writeToNbt() {
        CompoundTag tag = delegate.writeToNbt();
        ResonanceDiskAbilities.write(tag, abilityMask);
        return tag;
    }

    public static void rsi$stripSlotTag(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag != null) {
            tag.remove(RSI_SLOT_TAG);
            if (tag.isEmpty()) stack.setTag(null);
        }
    }
}
