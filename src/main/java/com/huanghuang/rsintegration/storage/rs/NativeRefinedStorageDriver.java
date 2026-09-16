package com.huanghuang.rsintegration.storage.rs;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.storage.StoragePermission;
import com.huanghuang.rsintegration.storage.StorageItemChangeListener;
import com.huanghuang.rsintegration.storage.StorageItemSubscription;
import com.refinedmods.refinedstorage.api.network.INetwork;
import com.refinedmods.refinedstorage.api.network.security.Permission;
import com.refinedmods.refinedstorage.api.storage.cache.IStorageCache;
import com.refinedmods.refinedstorage.api.storage.cache.IStorageCacheListener;
import com.refinedmods.refinedstorage.api.util.Action;
import com.refinedmods.refinedstorage.api.util.IComparer;
import com.refinedmods.refinedstorage.api.util.StackListResult;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

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
        return snapshotItems(null);
    }

    @Override
    public RefinedStorageSnapshotRead snapshotItems(java.util.Set<net.minecraft.world.item.Item> itemTypes) {
        requireAvailable();
        var cache = network.getItemStorageCache();
        if (cache == null || cache.getList() == null) return RefinedStorageSnapshotRead.unavailable();
        List<ItemStack> items = new ArrayList<>();
        var list = cache.getList();
        // RS's single-item bucket contains every NBT variant in native order.
        // Preserve full traversal for multi-type queries and unknown implementations.
        var candidates = itemTypes != null && itemTypes.size() == 1
                && list.getClass() == com.refinedmods.refinedstorage.apiimpl.util.ItemStackList.class
                ? list.getStacks(new ItemStack(itemTypes.iterator().next())) : list.getStacks();
        for (var entry : candidates) {
            ItemStack stack = entry.getStack();
            if (!stack.isEmpty() && stack.getCount() > 0
                    && (itemTypes == null || itemTypes.contains(stack.getItem()))) items.add(stack);
        }
        // The response takes defensive copies before these native references leave the driver.
        return RefinedStorageSnapshotRead.available(items);
    }

    @Override
    public Optional<StorageItemSubscription> subscribeItemChanges(StorageItemChangeListener listener) {
        Objects.requireNonNull(listener, "listener");
        requireAvailable();
        IStorageCache<ItemStack> cache = network.getItemStorageCache();
        if (cache == null) return Optional.empty();
        IStorageCacheListener<ItemStack> nativeListener = new IStorageCacheListener<>() {
            @Override public void onAttached() {}

            @Override
            public void onInvalidated() {
                listener.onInvalidated();
            }

            private void publish(StackListResult<ItemStack> result) {
                if (result == null || result.getStack() == null || result.getStack().isEmpty()) return;
                ItemStack changed = result.getStack();
                long absolute = 0L;
                try {
                    var list = cache.getList();
                    if (list != null) {
                        ItemStack cached = result.getId() == null ? null : list.get(result.getId());
                        if (cached == null || cached.isEmpty()) {
                            var entry = list.getEntry(changed, IComparer.COMPARE_NBT);
                            cached = entry == null ? null : entry.getStack();
                        }
                        if (cached != null && !cached.isEmpty()) absolute = Math.max(0, cached.getCount());
                    }
                    listener.onChanged(changed.copyWithCount(1), absolute);
                } catch (RuntimeException | LinkageError failure) {
                    listener.onInvalidated();
                }
            }

            @Override public void onChanged(StackListResult<ItemStack> result) { publish(result); }
            @Override public void onChangedBulk(List<StackListResult<ItemStack>> results) {
                if (results != null) results.forEach(this::publish);
            }
        };
        cache.addListener(nativeListener);
        return Optional.of(new StorageItemSubscription() {
            private boolean closed;

            @Override
            public boolean isValid() {
                if (closed) return false;
                try {
                    return network.canRun() && network.getItemStorageCache() == cache
                            && cache.getList() != null;
                } catch (RuntimeException | LinkageError ignored) {
                    return false;
                }
            }

            @Override
            public void close() {
                if (closed) return;
                closed = true;
                try {
                    cache.removeListener(nativeListener);
                } catch (RuntimeException | LinkageError ignored) {
                    // The cache may already have been invalidated and detached.
                }
            }
        });
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
