package com.huanghuang.rsintegration.storage.rs;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.storage.StoragePermission;
import com.refinedmods.refinedstorage.api.network.INetwork;
import com.refinedmods.refinedstorage.api.network.security.Permission;
import com.refinedmods.refinedstorage.api.util.Action;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Production RS driver. All native RS types stop at this class. */
final class NativeRefinedStorageDriver implements RefinedStorageDriver {
    private final INetwork network;

    NativeRefinedStorageDriver(INetwork network) {
        this.network = Objects.requireNonNull(network, "network");
    }

    @Override
    public boolean isAvailable() {
        try {
            // The handle was already authenticated by NetworkItem/container
            // resolution (or by resolveNetworkStrict for an explicit storage
            // reference). Do not require a second coordinate lookup to return
            // the identical INetwork instance: creative controllers and some
            // wireless terminal paths legitimately expose a handle whose
            // position is transient or whose wrapper is recreated by RS.
            if (!network.canRun() || network.getLevel() == null
                    || network.getItemStorageCache() == null
                    || network.getItemStorageCache().getList() == null) {
                RSIntegrationMod.LOGGER.debug(
                        "[RSI-Storage] RS session unavailable: running={} level={} cache={} list={}",
                        network.canRun(), network.getLevel() != null,
                        network.getItemStorageCache() != null,
                        network.getItemStorageCache() != null
                                && network.getItemStorageCache().getList() != null);
                return false;
            }
            return true;
        } catch (RuntimeException | LinkageError e) {
            RSIntegrationMod.LOGGER.debug("[RSI-Storage] RS session availability probe failed", e);
            return false;
        }
    }

    private void requireAvailable() {
        if (!isAvailable()) throw new RefinedStorageUnavailableException();
    }

    @Override
    public RefinedStorageSnapshotRead snapshotItems() {
        requireAvailable();
        var cache = network.getItemStorageCache();
        if (cache == null || cache.getList() == null) return RefinedStorageSnapshotRead.unavailable();
        List<ItemStack> items = new ArrayList<>();
        for (var entry : cache.getList().getStacks()) {
            ItemStack stack = entry.getStack();
            if (!stack.isEmpty() && stack.getCount() > 0) items.add(stack.copy());
        }
        return RefinedStorageSnapshotRead.available(items);
    }

    @Override
    public boolean hasPermission(ServerPlayer player, StoragePermission permission) {
        requireAvailable();
        var security = network.getSecurityManager();
        if (security == null) return true;
        if (permission == StoragePermission.VIEW) {
            return RefinedStoragePermissionRules.canView(
                    () -> security.hasPermission(Permission.INSERT, player),
                    () -> security.hasPermission(Permission.EXTRACT, player),
                    () -> security.hasPermission(Permission.AUTOCRAFTING, player));
        }
        Permission nativePermission = permission == StoragePermission.INSERT
                ? Permission.INSERT : Permission.EXTRACT;
        return security.hasPermission(nativePermission, player);
    }

    @Override
    public ItemStack extract(ItemStack template, int amount, boolean simulate) {
        requireAvailable();
        return network.extractItem(template, amount, simulate ? Action.SIMULATE : Action.PERFORM);
    }

    @Override
    public ItemStack insert(ItemStack stack, boolean simulate) {
        requireAvailable();
        return network.insertItem(stack, stack.getCount(), simulate ? Action.SIMULATE : Action.PERFORM);
    }

    @Override
    public void recordInsertion(ServerPlayer player, ItemStack accepted) {
        requireAvailable();
        var tracker = network.getItemStorageTracker();
        if (tracker != null) tracker.changed(player, accepted);
    }
}
