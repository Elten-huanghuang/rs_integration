package com.huanghuang.rsintegration.disk.rs;

import com.refinedmods.refinedstorage.api.storage.AccessType;
import com.refinedmods.refinedstorage.api.storage.disk.IStorageDisk;
import com.refinedmods.refinedstorage.api.storage.disk.IStorageDiskContainerContext;
import com.refinedmods.refinedstorage.api.storage.disk.IStorageDiskListener;
import com.refinedmods.refinedstorage.api.util.Action;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** RS SavedData 中只存轻量代理；没有租约的根代理不暴露可读写库存。 */
public final class UnifiedDiskRoot implements IStorageDisk<ItemStack> {
    public static final ResourceLocation FACTORY_ID = new ResourceLocation("rs_integration", "unified");
    private final UnifiedDiskManager manager;
    private final UUID id, worldId, owner;
    private final CompoundTag reference;

    public UnifiedDiskRoot(UnifiedDiskManager manager, UUID id, UUID worldId, UUID owner) {
        this.manager = manager; this.id = id; this.worldId = worldId; this.owner = owner;
        reference = new CompoundTag();
        reference.putInt("Format", 1); reference.putUUID("Disk", id); reference.putUUID("World", worldId);
        if (owner != null) reference.putUUID("Owner", owner);
    }

    public UnifiedDiskRoot(UnifiedDiskManager manager, CompoundTag reference) {
        this.manager = manager; this.reference = reference.copy();
        id = reference.hasUUID("Disk") ? reference.getUUID("Disk") : null;
        worldId = reference.hasUUID("World") ? reference.getUUID("World") : null;
        owner = reference.hasUUID("Owner") ? reference.getUUID("Owner") : null;
    }

    public UnifiedDiskManager manager() { return manager; }
    public UUID id() { return id; }
    public UUID worldId() { return worldId; }
    public boolean compatible() { return id != null && worldId != null && reference.getInt("Format") == 1; }
    @Override public int getCapacity() { return -1; }
    @Override public UUID getOwner() { return owner; }
    @Override public Collection<ItemStack> getStacks() { return List.of(); }
    @Override public ItemStack insert(ItemStack stack, int size, Action action) { return stack.copyWithCount(Math.max(0, size)); }
    @Override public ItemStack extract(ItemStack stack, int size, int flags, Action action) { return ItemStack.EMPTY; }
    @Override public int getStored() { return 0; }
    @Override public int getPriority() { return 0; }
    @Override public AccessType getAccessType() { return AccessType.INSERT_EXTRACT; }
    @Override public int getCacheDelta(int storedPreInsertion, int size, ItemStack remainder) { return 0; }
    @Override public void setSettings(IStorageDiskListener listener, IStorageDiskContainerContext context) {}
    @Override public ResourceLocation getFactoryId() { return FACTORY_ID; }
    @Override public CompoundTag writeToNbt() {
        return reference.copy();
    }
}
