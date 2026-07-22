package com.huanghuang.rsintegration.resonance.disk;

import com.refinedmods.refinedstorage.api.storage.AccessType;
import com.refinedmods.refinedstorage.api.storage.disk.IStorageDisk;
import com.refinedmods.refinedstorage.api.storage.disk.IStorageDiskContainerContext;
import com.refinedmods.refinedstorage.api.storage.disk.IStorageDiskListener;
import com.refinedmods.refinedstorage.api.util.Action;
import com.refinedmods.refinedstorage.api.util.IComparer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class ResonanceDiskWrapper implements IStorageDisk<ItemStack> {

    public static final ResourceLocation FACTORY_ID =
            new ResourceLocation("rs_integration", "resonance");

    private static final String RSI_SLOT_TAG = "RSISlot";

    private final IStorageDisk<ItemStack> delegate;

    public ResonanceDiskWrapper(IStorageDisk<ItemStack> delegate) {
        this.delegate = delegate;
    }

    public static boolean isLogicallyNonStackable(ItemStack stack) {
        if (stack.isEmpty()) return false;
        if (stack.getMaxStackSize() <= 1 || stack.isDamageableItem()) return true;

        // Equipment and capability-backed items can advertise a normal stack
        // size while their persistent NBT represents an individual state.
        // Keeping every NBT-bearing variant in its own logical slot prevents
        // equal-state equipment from being merged and later duplicated on take.
        if (stack.getTag() != null && stack.getTag().getAllKeys().stream()
                .anyMatch(key -> !RSI_SLOT_TAG.equals(key))) return true;

        // Some modded weapons incorrectly advertise a stack size above one.
        // Main-hand combat attributes are a more reliable cross-mod signal.
        try {
            var modifiers = stack.getAttributeModifiers(EquipmentSlot.MAINHAND);
            if (modifiers.containsKey(Attributes.ATTACK_DAMAGE)
                    || modifiers.containsKey(Attributes.ATTACK_SPEED)) return true;
        } catch (RuntimeException ignored) {
            // A broken third-party attribute provider must not break disk access.
        }

        // SlashBlade can expose its combat state through capabilities instead of
        // vanilla attributes, so retain an explicit hierarchy fallback.
        for (Class<?> type = stack.getItem().getClass(); type != null; type = type.getSuperclass()) {
            if ("mods.flammpfeil.slashblade.item.ItemSlashBlade".equals(type.getName())) return true;
        }
        return false;
    }

    /** Item identity used by logical backpack slots; NBT variants must never merge. */
    public static boolean isSameVariant(ItemStack first, ItemStack second) {
        return ResonanceStackIdentity.isSameVariant(first, second);
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
        if (!result.isEmpty()) rsi$stripSlotTag(result);
        return result;
    }

    /** Moves one exact stored variant to a different logical slot identity. */
    public boolean moveSlot(ItemStack exactStoredStack, int newSlot) {
        if (exactStoredStack.isEmpty()) return false;
        ItemStack simulated = delegate.extract(
                exactStoredStack, exactStoredStack.getCount(), 0, Action.SIMULATE);
        if (!sameExactTaggedStack(exactStoredStack, simulated)) return false;
        ItemStack removed = delegate.extract(exactStoredStack, exactStoredStack.getCount(), 0, Action.PERFORM);
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
    }

    /** Repairs legacy stacks of items that must occupy one logical slot per item. */
    public int splitLogicallyNonStackableStacks() {
        List<ItemStack> snapshot = new ArrayList<>();
        Set<Integer> usedSlots = new HashSet<>();
        for (ItemStack stored : delegate.getStacks()) {
            ItemStack exact = stored.copy();
            snapshot.add(exact);
            CompoundTag tag = exact.getTag();
            if (tag != null && tag.contains(RSI_SLOT_TAG)) usedSlots.add(tag.getInt(RSI_SLOT_TAG));
        }

        int split = 0;
        int nextSlot = 0;
        for (ItemStack exact : snapshot) {
            if (exact.getCount() <= 1 || !isLogicallyNonStackable(exact)) continue;
            ItemStack removed = delegate.extract(exact, exact.getCount(), 0, Action.PERFORM);
            if (removed.getCount() != exact.getCount()) {
                if (!removed.isEmpty()) delegate.insert(removed, removed.getCount(), Action.PERFORM);
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
    }

    public ItemStack manualExtractExact(ItemStack exactTaggedStack, int size, int flags, Action action) {
        ItemStack result = delegate.extract(exactTaggedStack, size, flags | IComparer.COMPARE_NBT, action);
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
        if (!newStack.isEmpty() && newStack.getCount() > (isLogicallyNonStackable(newStack) ? 1 : newStack.getMaxStackSize()))
            return SlotMutationResult.REJECTED;

        if (!oldStack.isEmpty() && !newStack.isEmpty()
                && isSameVariant(oldStack, newStack)) {
            int delta = newStack.getCount() - oldStack.getCount();
            return delta > 0
                    ? insertExact(slot, newStack, delta)
                    : extractExact(slot, oldStack, -delta);
        }

        if (!oldStack.isEmpty()) {
            ItemStack simulated = manualExtract(slot, oldStack, oldStack.getCount(), 0, Action.SIMULATE);
            if (!sameExtractedVariant(oldStack, simulated)) return SlotMutationResult.REJECTED;
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
