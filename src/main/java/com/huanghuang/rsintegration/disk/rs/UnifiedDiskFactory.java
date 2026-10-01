package com.huanghuang.rsintegration.disk.rs;

import com.huanghuang.rsintegration.ModItems;
import com.refinedmods.refinedstorage.api.storage.disk.IStorageDisk;
import com.refinedmods.refinedstorage.api.storage.disk.IStorageDiskFactory;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

import java.io.IOException;
import java.util.UUID;

public final class UnifiedDiskFactory implements IStorageDiskFactory<ItemStack> {
    @Override public IStorageDisk<ItemStack> createFromNbt(ServerLevel level, CompoundTag tag) {
        // 未知未来格式或坏代理也保留原文，不让单个坏盘中断整个 RS 磁盘管理器加载。
        return new UnifiedDiskRoot(UnifiedDiskManager.get(level), tag);
    }

    @Override public IStorageDisk<ItemStack> create(ServerLevel level, int capacity, UUID owner) {
        try { return UnifiedDiskManager.get(level).create(owner); }
        catch (IOException e) { throw new IllegalStateException("统一盘无法创建", e); }
    }

    @Override public ItemStack createDiskItem(IStorageDisk<ItemStack> disk, UUID id) {
        if (!(disk instanceof UnifiedDiskRoot root) || !root.compatible() || !root.id().equals(id)) throw new IllegalArgumentException("统一盘代理身份不一致");
        ItemStack stack = new ItemStack(ModItems.UNIFIED_STORAGE_DISK.get());
        UnifiedDiskItem item = (UnifiedDiskItem) stack.getItem();
        item.setIdentity(stack, id, root.worldId());
        return stack;
    }
}
