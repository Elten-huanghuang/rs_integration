package com.huanghuang.rsintegration.resonance.disk;

import com.refinedmods.refinedstorage.api.storage.AccessType;
import com.refinedmods.refinedstorage.api.storage.disk.IStorageDisk;
import com.refinedmods.refinedstorage.api.storage.disk.IStorageDiskContainerContext;
import com.refinedmods.refinedstorage.api.storage.disk.IStorageDiskListener;
import com.refinedmods.refinedstorage.api.util.Action;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

public final class ResonanceDiskWrapper implements IStorageDisk<ItemStack> {

    public static final ResourceLocation FACTORY_ID =
            new ResourceLocation("rs_integration", "resonance");

    private static final String RSI_SLOT_TAG = "RSISlot";

    private final IStorageDisk<ItemStack> delegate;

    public ResonanceDiskWrapper(IStorageDisk<ItemStack> delegate) {
        this.delegate = delegate;
    }

    public IStorageDisk<ItemStack> delegate() {
        return delegate;
    }

    @Override
    public ItemStack insert(ItemStack stack, int size, Action action) {
        return stack;
    }

    public ItemStack manualInsert(int slot, ItemStack stack, int size, Action action) {
        ItemStack tagged = stack.copy();
        tagged.getOrCreateTag().putInt(RSI_SLOT_TAG, slot);
        return delegate.insert(tagged, size, action);
    }

    @Override
    public ResourceLocation getFactoryId() {
        return FACTORY_ID;
    }

    @Override
    public Collection<ItemStack> getStacks() {
        List<ItemStack> raw = new ArrayList<>();
        for (ItemStack s : delegate.getStacks()) raw.add(s.copy());
        raw.sort(Comparator.comparingInt(s ->
                s.getTag() != null ? s.getTag().getInt(RSI_SLOT_TAG) : Integer.MAX_VALUE));
        for (ItemStack s : raw) rsi$stripSlotTag(s);
        return raw;
    }

    @Override
    public ItemStack extract(ItemStack stack, int size, int flags, Action action) {
        return ItemStack.EMPTY;
    }

    public ItemStack manualExtract(int slot, ItemStack template, int size, int flags, Action action) {
        ItemStack tagged = template.copy();
        tagged.getOrCreateTag().putInt(RSI_SLOT_TAG, slot);
        ItemStack result = delegate.extract(tagged, size, flags, action);
        if (!result.isEmpty()) rsi$stripSlotTag(result);
        return result;
    }

    public ItemStack manualExtractExact(ItemStack exactTaggedStack, int size, int flags, Action action) {
        ItemStack result = delegate.extract(exactTaggedStack, size, flags, action);
        if (!result.isEmpty()) rsi$stripSlotTag(result);
        return result;
    }

    public enum SlotMutationResult {
        SUCCESS,
        REJECTED,
        RECOVERY_FAILED
    }

    /** Atomically reconcile one logical backpack slot against the disk delegate. */
    public SlotMutationResult reconcileSlot(int slot, ItemStack previous, ItemStack requested) {
        ItemStack oldStack = sanitized(previous);
        ItemStack newStack = sanitized(requested);
        if (sameStack(oldStack, newStack)) return SlotMutationResult.SUCCESS;

        if (!oldStack.isEmpty() && !newStack.isEmpty()
                && ItemStack.isSameItemSameTags(oldStack, newStack)) {
            int delta = newStack.getCount() - oldStack.getCount();
            return delta > 0
                    ? insertExact(slot, newStack, delta)
                    : extractExact(slot, oldStack, -delta);
        }

        if (!oldStack.isEmpty()) {
            ItemStack simulated = manualExtract(slot, oldStack, oldStack.getCount(), 0, Action.SIMULATE);
            if (simulated.getCount() != oldStack.getCount()) return SlotMutationResult.REJECTED;
        }
        if (!newStack.isEmpty()
                && getCapacity() - getStored() + oldStack.getCount() < newStack.getCount()) {
            return SlotMutationResult.REJECTED;
        }

        ItemStack removed = ItemStack.EMPTY;
        if (!oldStack.isEmpty()) {
            removed = manualExtract(slot, oldStack, oldStack.getCount(), 0, Action.PERFORM);
            if (removed.getCount() != oldStack.getCount()) {
                return restore(slot, removed)
                        ? SlotMutationResult.REJECTED : SlotMutationResult.RECOVERY_FAILED;
            }
        }

        if (newStack.isEmpty()) return SlotMutationResult.SUCCESS;
        ItemStack simulatedRemainder = manualInsert(slot, newStack, newStack.getCount(), Action.SIMULATE);
        if (!simulatedRemainder.isEmpty()) {
            return restore(slot, removed)
                    ? SlotMutationResult.REJECTED : SlotMutationResult.RECOVERY_FAILED;
        }

        ItemStack remainder = manualInsert(slot, newStack, newStack.getCount(), Action.PERFORM);
        if (remainder.isEmpty()) return SlotMutationResult.SUCCESS;

        int inserted = newStack.getCount() - remainder.getCount();
        boolean removedNew = inserted <= 0 || extractedExactly(slot, newStack, inserted);
        boolean restoredOld = restore(slot, removed);
        return removedNew && restoredOld
                ? SlotMutationResult.REJECTED : SlotMutationResult.RECOVERY_FAILED;
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
        if (simulated.getCount() != count) return SlotMutationResult.REJECTED;
        ItemStack extracted = manualExtract(slot, template, count, 0, Action.PERFORM);
        if (extracted.getCount() == count) return SlotMutationResult.SUCCESS;
        return restore(slot, extracted)
                ? SlotMutationResult.REJECTED : SlotMutationResult.RECOVERY_FAILED;
    }

    private boolean extractedExactly(int slot, ItemStack template, int count) {
        ItemStack extracted = manualExtract(slot, template, count, 0, Action.PERFORM);
        return extracted.getCount() == count;
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
        return first.getCount() == second.getCount() && ItemStack.isSameItemSameTags(first, second);
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

    @Override
    public CompoundTag writeToNbt() {
        return delegate.writeToNbt();
    }

    public static void rsi$stripSlotTag(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag != null) {
            tag.remove(RSI_SLOT_TAG);
            if (tag.isEmpty()) stack.setTag(null);
        }
    }
}
