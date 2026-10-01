package com.huanghuang.rsintegration.disk.rs;

import com.huanghuang.rsintegration.disk.core.FrozenKey;
import com.huanghuang.rsintegration.disk.core.ResourceTable;
import com.huanghuang.rsintegration.disk.core.UnifiedDiskCore;
import com.refinedmods.refinedstorage.api.storage.AccessType;
import com.refinedmods.refinedstorage.api.storage.disk.IStorageDisk;
import com.refinedmods.refinedstorage.api.storage.disk.IStorageDiskContainerContext;
import com.refinedmods.refinedstorage.api.storage.disk.IStorageDiskListener;
import com.refinedmods.refinedstorage.api.util.Action;
import com.refinedmods.refinedstorage.api.util.IComparer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** 每次挂载创建两个独立 listener/context 的适配器，数量列与模板互不影响。 */
public final class UnifiedBoundDisk<T> implements IStorageDisk<T> {
    private final UnifiedMountCoordinator.Lease lease;
    private final UnifiedDiskCore core;
    private final FrozenKey.Kind kind;
    private IStorageDiskListener listener;
    private IStorageDiskContainerContext context;

    public UnifiedBoundDisk(UnifiedMountCoordinator.Lease lease, UnifiedDiskCore core, FrozenKey.Kind kind) {
        this.lease = lease; this.core = core; this.kind = kind;
    }

    public ResourceTable table() { return core.table(kind); }
    public boolean ready() { core.checkThread(); return lease.valid(); }
    private int exactSlot(T source) { return kind == FrozenKey.Kind.ITEM ? table().exactSlot((ItemStack) source) : table().exactSlot((FluidStack) source); }
    private Object type(T stack) { return kind == FrozenKey.Kind.ITEM ? ((ItemStack) stack).getItem() : ((FluidStack) stack).getFluid(); }
    private int amount(T stack) { return kind == FrozenKey.Kind.ITEM ? ((ItemStack) stack).getCount() : ((FluidStack) stack).getAmount(); }
    @SuppressWarnings("unchecked") private T stack(FrozenKey key, int amount) { return (T) (kind == FrozenKey.Kind.ITEM ? key.itemStack(amount) : key.fluidStack(amount)); }
    @SuppressWarnings("unchecked") private T empty() { return (T) (kind == FrozenKey.Kind.ITEM ? ItemStack.EMPTY : FluidStack.EMPTY); }
    private T copy(T source, int amount) {
        if (amount <= 0) return empty();
        if (kind == FrozenKey.Kind.ITEM) return cast(((ItemStack) source).copyWithCount(amount));
        FluidStack copy = ((FluidStack) source).copy(); copy.setAmount(amount); return cast(copy);
    }
    @SuppressWarnings("unchecked") private T cast(Object value) { return (T) value; }

    @Override public Collection<T> getStacks() {
        if (!ready()) return List.of();
        List<T> result = new ArrayList<>(table().size());
        table().forEach((key, amount) -> result.add(stack(key, amount)));
        return result;
    }

    @Override public T insert(T source, int size, Action action) {
        if (size <= 0) return empty();
        if (!ready() || getAccessType() == AccessType.EXTRACT || amount(source) <= 0
                || kind == FrozenKey.Kind.ITEM && ((ItemStack) source).getItem() instanceof UnifiedDiskItem) return copy(source, size);
        int accepted;
        try { accepted = kind == FrozenKey.Kind.ITEM
                ? core.insertItem((ItemStack) source, size, action == Action.PERFORM)
                : core.insertFluid((FluidStack) source, size, action == Action.PERFORM); }
        catch (IllegalArgumentException e) { return copy(source, size); }
        if (accepted > 0 && action == Action.PERFORM && listener != null) listener.onChanged();
        return copy(source, size - accepted);
    }

    @Override public T extract(T source, int size, int flags, Action action) {
        if (size <= 0 || !ready() || getAccessType() == AccessType.INSERT || amount(source) <= 0) return empty();
        int slot;
        if ((flags & IComparer.COMPARE_NBT) != 0) {
            try { slot = exactSlot(source); }
            catch (IllegalArgumentException e) { return empty(); }
        } else slot = table().first(type(source));
        if ((flags & IComparer.COMPARE_QUANTITY) != 0) {
            if ((flags & IComparer.COMPARE_NBT) != 0) {
                if (slot >= 0 && table().amount(slot) != amount(source)) slot = -1;
            } else {
                while (slot >= 0 && table().amount(slot) != amount(source)) slot = table().next(slot);
            }
        }
        if (slot < 0) return empty();
        FrozenKey key = table().key(slot);
        int taken = core.extract(kind, slot, size, action == Action.PERFORM);
        if (taken > 0 && action == Action.PERFORM && listener != null) listener.onChanged();
        return stack(key, taken);
    }

    @Override public int getStored() { return ready() ? table().total() : 0; }
    @Override public int getCapacity() { return -1; }
    @Override public UUID getOwner() { return core.owner; }
    @Override public int getPriority() { return 0; }
    @Override public AccessType getAccessType() { return context == null ? AccessType.INSERT_EXTRACT : context.getAccessType(); }
    @Override public int getCacheDelta(int storedPreInsertion, int size, T remainder) {
        return !ready() || getAccessType() == AccessType.INSERT ? 0 : size - (remainder == null ? 0 : amount(remainder));
    }
    @Override public void setSettings(IStorageDiskListener listener, IStorageDiskContainerContext context) { this.listener = listener; this.context = context; }
    @Override public ResourceLocation getFactoryId() { return UnifiedDiskRoot.FACTORY_ID; }
    @Override public CompoundTag writeToNbt() { return lease.root.writeToNbt(); }
}
