package com.huanghuang.rsintegration.resonance.disk;

import com.huanghuang.rsintegration.resonance.item.ResonanceDiskItem;
import com.refinedmods.refinedstorage.api.IRSAPI;
import com.refinedmods.refinedstorage.api.storage.disk.IStorageDisk;
import com.refinedmods.refinedstorage.api.storage.disk.IStorageDiskFactory;
import com.refinedmods.refinedstorage.apiimpl.API;
import com.refinedmods.refinedstorage.apiimpl.storage.disk.factory.ItemStorageDiskFactory;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

import java.util.UUID;

public final class ResonanceDiskFactory implements IStorageDiskFactory<ItemStack> {

    private IStorageDiskFactory<ItemStack> rsFactory;

    public ResonanceDiskFactory() {
        IRSAPI api = API.instance();
        var registry = api.getStorageDiskRegistry();
        this.rsFactory = registry != null
                ? (IStorageDiskFactory<ItemStack>) registry.get(ItemStorageDiskFactory.ID)
                : null;
    }

    private IStorageDiskFactory<ItemStack> rsFactoryOrThrow() {
        if (rsFactory == null) {
            IRSAPI api = API.instance();
            var registry = api.getStorageDiskRegistry();
            this.rsFactory = registry != null
                    ? (IStorageDiskFactory<ItemStack>) registry.get(ItemStorageDiskFactory.ID)
                    : null;
        }
        if (rsFactory == null) {
            throw new IllegalStateException("ResonanceDiskFactory: RS storage disk registry not available");
        }
        return rsFactory;
    }

    @Override
    public IStorageDisk<ItemStack> createFromNbt(ServerLevel level, CompoundTag tag) {
        return new ResonanceDiskWrapper(rsFactoryOrThrow().createFromNbt(level, tag));
    }

    @Override
    public IStorageDisk<ItemStack> create(ServerLevel level, int capacity, UUID owner) {
        return new ResonanceDiskWrapper(rsFactoryOrThrow().create(level, capacity, owner));
    }

    @Override
    public ItemStack createDiskItem(IStorageDisk<ItemStack> disk, UUID id) {
        ItemStack stack = new ItemStack(ResonanceDiskItem.INSTANCE);
        ((ResonanceDiskItem) stack.getItem()).setId(stack, id);
        return stack;
    }
}
