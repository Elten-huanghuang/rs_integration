package com.huanghuang.rsintegration.disk.rs;

import com.huanghuang.rsintegration.disk.core.FrozenKey;
import com.refinedmods.refinedstorage.api.storage.disk.IStorageDisk;
import com.refinedmods.refinedstorage.apiimpl.network.node.NetworkNode;
import com.refinedmods.refinedstorage.util.StackUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.function.Function;

public final class UnifiedDiskMounts {
    private static final Logger LOGGER = LogManager.getLogger(UnifiedDiskMounts.class);
    private UnifiedDiskMounts() {}

    public static boolean create(ServerLevel level, ItemStack stack, int slot, IStorageDisk<ItemStack>[] items,
                                 IStorageDisk<FluidStack>[] fluids,
                                 Function<IStorageDisk<ItemStack>, IStorageDisk> itemWrapper,
                                 Function<IStorageDisk<FluidStack>, IStorageDisk> fluidWrapper) {
        NetworkNode node = UnifiedMountCoordinator.caller();
        UnifiedDiskManager existing = UnifiedDiskManager.existing(level.getServer());
        if (existing != null) existing.mounts().releaseSlot(node, slot);
        if (!(stack.getItem() instanceof UnifiedDiskItem item)) return false;
        items[slot] = null; fluids[slot] = null;
        try {
            UnifiedDiskManager manager = UnifiedDiskManager.get(level);
            if (!manager.enabled() || node == null || !item.initialize(stack, level, node.getOwner())) return true;
            UnifiedDiskRoot root = manager.resolve(stack, level);
            if (root == null) return true;
            UnifiedDiskManager.Entry entry = manager.entry(root.id());
            if (entry.core == null) return true;
            Runnable retry = () -> UnifiedMountCoordinator.withCaller(node, () -> {
                StackUtils.createStorages(level, stack, slot, items, fluids, itemWrapper, fluidWrapper);
                return null;
            });
            UnifiedMountCoordinator.Lease lease = manager.mounts().acquire(root, node, slot, items, fluids, stack, retry);
            if (lease == null) return true;
            items[slot] = itemWrapper.apply(new UnifiedBoundDisk<>(lease, entry.core, FrozenKey.Kind.ITEM));
            fluids[slot] = fluidWrapper.apply(new UnifiedBoundDisk<>(lease, entry.core, FrozenKey.Kind.FLUID));
        } catch (RuntimeException e) {
            items[slot] = null; fluids[slot] = null;
            UnifiedDiskManager failed = UnifiedDiskManager.existing(level.getServer());
            if (failed != null) failed.mounts().releaseSlot(node, slot);
            LOGGER.error("[RSI] 统一盘挂载失败，两个库存视图均禁用", e);
        }
        return true;
    }
}
