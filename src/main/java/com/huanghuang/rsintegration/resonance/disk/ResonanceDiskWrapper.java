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
        return stack; // reject all auto-routing
    }

    /** Manual insert from backpack UI — accepts everything, tags with slot. */
    public ItemStack manualInsert(int slot, ItemStack stack, int size, Action action) {
        ItemStack tagged = stack.copy();
        tagged.getOrCreateTag().putInt(RSI_SLOT_TAG, slot);
        return delegate.insert(tagged, size, action);
    }

    @Override
    public ResourceLocation getFactoryId() {
        return FACTORY_ID;
    }

    /**
     * Returns stacks sorted by slot index, with the slot tag stripped.
     * Used by the backpack UI to restore slot positions.
     */
    @Override
    public Collection<ItemStack> getStacks() {
        List<ItemStack> raw = new ArrayList<>();
        for (ItemStack s : delegate.getStacks()) raw.add(s.copy());
        raw.sort(Comparator.comparingInt(s ->
                s.getTag() != null ? s.getTag().getInt(RSI_SLOT_TAG) : Integer.MAX_VALUE));
        for (ItemStack s : raw) rsi$stripSlotTag(s);
        return raw;
    }

    /** Block RS auto-extraction — only the backpack UI and passive effects may extract. */
    @Override
    public ItemStack extract(ItemStack stack, int size, int flags, Action action) {
        return ItemStack.EMPTY;
    }

    /**
     * Extract from a known backpack slot. Reconstructs the RSISlot tag on the
     * template so the NBT-exact delegate.extract can find the tagged item.
     */
    public ItemStack manualExtract(int slot, ItemStack template, int size, int flags, Action action) {
        ItemStack tagged = template.copy();
        tagged.getOrCreateTag().putInt(RSI_SLOT_TAG, slot);
        ItemStack result = delegate.extract(tagged, size, flags, action);
        if (!result.isEmpty()) rsi$stripSlotTag(result);
        return result;
    }

    /**
     * Extract using an already-tagged stack (e.g. from delegate().getStacks()).
     * The template's NBT must exactly match what is stored in the delegate.
     */
    public ItemStack manualExtractExact(ItemStack exactTaggedStack, int size, int flags, Action action) {
        ItemStack result = delegate.extract(exactTaggedStack, size, flags, action);
        if (!result.isEmpty()) rsi$stripSlotTag(result);
        return result;
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
