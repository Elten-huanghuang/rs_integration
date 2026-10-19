package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.mods.ironsspellbooks.InkFluidSupport;
import com.huanghuang.rsintegration.network.RSIntegrationNetwork;
import com.huanghuang.rsintegration.resonance.api.ResonanceStorageView;
import com.huanghuang.rsintegration.resonance.bridge.RSResonanceDiskAccess;
import com.huanghuang.rsintegration.storage.StorageOperationResult;
import com.huanghuang.rsintegration.storage.StorageSession;
import com.huanghuang.rsintegration.storage.StorageSnapshotResult;
import com.refinedmods.refinedstorage.api.network.INetwork;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.entity.player.Player;
import com.huanghuang.rsintegration.storage.rs.RefinedStorageBackend;
import com.huanghuang.rsintegration.storage.StorageBackendId;
import com.huanghuang.rsintegration.storage.StorageItemKey;
import com.huanghuang.rsintegration.storage.StorageOperationMode;
import com.huanghuang.rsintegration.storage.StoragePermission;
import com.huanghuang.rsintegration.storage.StoragePermissionResult;
import com.huanghuang.rsintegration.storage.StorageNetworkLocation;
import com.huanghuang.rsintegration.storage.StorageReference;
import com.huanghuang.rsintegration.storage.StorageSnapshot;
import com.huanghuang.rsintegration.storage.StorageSnapshotStatus;
import com.huanghuang.rsintegration.storage.StoredItem;
import com.huanghuang.rsintegration.util.ItemStackUtils;
import com.huanghuang.rsintegration.util.LogSampler;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.Optional;
import net.minecraft.world.item.Item;
import net.minecraft.core.registries.BuiltInRegistries;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Objects;

/**
 * Transitional adapter for callers that still hold an RS native network.
 * New crafting code must depend on {@link CraftStorageEndpoint}; this class
 * is the only compatibility bridge that retains the native RS handle.
 */
final class LegacyRsCraftStorageEndpoint implements CraftStorageEndpoint {
    private final INetwork network;
    private final StorageSession session;

    LegacyRsCraftStorageEndpoint(INetwork network) {
        this.network = Objects.requireNonNull(network, "network");
        this.session = new LegacyRsStorageSession(network);
    }

    INetwork network() { return network; }

    /** 保留 RS 原生缓存顺序与 NBT 变体，供规划器构造材料视图。 */
    List<ItemStack> cachedStackVariants() {
        var cache = network.getItemStorageCache();
        if (cache == null || cache.getList() == null) return List.of();
        List<ItemStack> stacks = new ArrayList<>();
        for (var entry : cache.getList().getStacks()) {
            ItemStack stack = entry.getStack();
            if (!stack.isEmpty()) stacks.add(stack);
        }
        stacks.addAll(InkFluidSupport.snapshot(network, null));
        return stacks;
    }

    String cacheKey() {
        try {
            return session().reference().toString();
        } catch (RuntimeException ignored) {
            return "legacy@" + System.identityHashCode(network);
        }
    }

    @Nullable
    ResonanceStorageView resonanceView() {
        return RSResonanceDiskAccess.find(network);
    }

    @Override public StorageSession session() { return session; }

    @Override
    public StorageOperationResult insert(ItemStack stack, boolean simulate) {
        ItemStack remainder = InkFluidSupport.isToken(stack) ? InkFluidSupport.insert(network, stack, simulate)
                : network.insertItem(stack.copy(), stack.getCount(),
                simulate ? com.refinedmods.refinedstorage.api.util.Action.SIMULATE
                        : com.refinedmods.refinedstorage.api.util.Action.PERFORM);
        return StorageOperationResult.inserted(
                simulate ? StorageOperationMode.SIMULATE
                        : StorageOperationMode.PERFORM,
                stack, remainder);
    }

    @Override
    public StorageOperationResult insert(Player player, ItemStack stack, boolean simulate) {
        ItemStack remainder = InkFluidSupport.isToken(stack) ? InkFluidSupport.insert(network, stack, simulate)
                : network.insertItem(stack.copy(), stack.getCount(),
                simulate ? com.refinedmods.refinedstorage.api.util.Action.SIMULATE
                        : com.refinedmods.refinedstorage.api.util.Action.PERFORM);
        if (!simulate && player != null) {
            ItemStack accepted = stack.copy();
            accepted.shrink(remainder.getCount());
            if (!accepted.isEmpty()) {
                if (InkFluidSupport.isToken(accepted)) {
                    var tracker = network.getFluidStorageTracker();
                    if (tracker != null) tracker.changed(player, InkFluidSupport.fluid(accepted));
                } else {
                    var tracker = network.getItemStorageTracker();
                    if (tracker != null) tracker.changed(player, accepted);
                }
                if (player instanceof ServerPlayer serverPlayer) {
                    MaterialSources.invalidateFor(serverPlayer, this);
                    serverPlayer.containerMenu.broadcastChanges();
                }
            }
        }
        return StorageOperationResult.inserted(
                simulate ? StorageOperationMode.SIMULATE
                        : StorageOperationMode.PERFORM,
                stack, remainder);
    }

    @Override
    public StorageOperationResult extractExact(Player player, ItemStack template,
                                               long amount, boolean simulate) {
        ItemStack result = InkFluidSupport.isToken(template)
                ? InkFluidSupport.extract(network, template, Math.toIntExact(amount), simulate)
                : RSIntegrationNetwork.extractExactFromNetwork(network, template,
                Math.toIntExact(amount), player instanceof ServerPlayer sp ? sp : null, simulate);
        return StorageOperationResult.extracted(
                simulate ? StorageOperationMode.SIMULATE
                        : StorageOperationMode.PERFORM,
                amount, result.isEmpty() ? List.of() : List.of(result));
    }

    private static final class LegacyRsStorageSession implements StorageSession {
        private static final Logger LOGGER = LogManager.getLogger(LegacyRsStorageSession.class);
        private static final LogSampler SKIPPED_ITEM_WARNINGS = new LogSampler(60_000L);
        private final INetwork network;
        private StorageSession matchingSession;
        LegacyRsStorageSession(INetwork network) { this.network = network; }

        @Override public StorageReference reference() {
            // Keep the transitional endpoint on the same canonical reference
            // format as RefinedStorageBackend. Older packets used the raw
            // "dimension@BlockPos{...}" form, which the typed resolver cannot
            // parse and made an otherwise usable RS network appear missing on
            // the next recipe-tree request.
            var level = network.getLevel();
            var position = network.getPosition();
            if (level == null || position == null) {
                throw new IllegalStateException("RS network has no location");
            }
            return new StorageReference(
                    new StorageBackendId("refinedstorage"),
                    "v1|" + level.dimension().location() + "@"
                            + position.getX() + "," + position.getY() + "," + position.getZ());
        }

        @Override public boolean isAvailable() {
            try {
                return network.canRun() && network.getLevel() != null
                        && network.getItemStorageCache() != null
                        && network.getItemStorageCache().getList() != null;
            } catch (RuntimeException | LinkageError ignored) {
                return false;
            }
        }

        @Override public Optional<StorageNetworkLocation> location() {
            try {
                var level = network.getLevel();
                var position = network.getPosition();
                return level == null || position == null ? Optional.empty()
                        : Optional.of(new StorageNetworkLocation(
                                level.dimension(), position.immutable()));
            } catch (RuntimeException | LinkageError ignored) {
                return Optional.empty();
            }
        }

        @Override public StorageSnapshotResult snapshotItems(ServerPlayer player) {
            return snapshotItems(player, null);
        }

        @Override public StorageSnapshotResult snapshotItems(ServerPlayer player,
                Set<Item> itemTypes) {
            var cache = network.getItemStorageCache();
            if (cache == null || cache.getList() == null) {
                return StorageSnapshotResult.failure(StorageSnapshotStatus.UNAVAILABLE);
            }
            List<StoredItem> items = new ArrayList<>();
            var list = cache.getList();
            // A native single-item bucket keeps every NBT variant in RS order.
            // Multi-type bucket concatenation would change the global order and
            // could make an ANY ingredient choose a different variant.
            var candidates = itemTypes != null && itemTypes.size() == 1
                    && list.getClass() == com.refinedmods.refinedstorage.apiimpl.util.ItemStackList.class
                    ? list.getStacks(new ItemStack(itemTypes.iterator().next()))
                    : list.getStacks();
            for (var entry : candidates) {
                ItemStack stack = entry.getStack();
                if (!stack.isEmpty() && stack.getCount() > 0
                        && (itemTypes == null || itemTypes.contains(stack.getItem()))) {
                    addSnapshotItem(items, stack);
                }
            }
            for (ItemStack token : InkFluidSupport.snapshot(network, itemTypes)) {
                addSnapshotItem(items, token);
            }
            return StorageSnapshotResult.success(new StorageSnapshot(
                    reference().backendId(), items));
        }

        private void addSnapshotItem(List<StoredItem> items, ItemStack stack) {
            try {
                items.add(new StoredItem(itemKey(stack), stack.getCount()));
            } catch (IllegalArgumentException failure) {
                // 单个食物的异常 NBT 不应让整个 RS 网络的合成材料消失。
                var itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
                if (SKIPPED_ITEM_WARNINGS.allow(reference().networkId() + ":" + itemId)) {
                    LOGGER.warn(
                            "[RSI-Storage] legacy RS snapshot skipped unsupported item network={} item={} cause={}; other items remain available",
                            reference().networkId(), itemId, failure.toString());
                }
            }
        }

        @Override public StoragePermissionResult checkPermission(
                ServerPlayer player, StoragePermission permission) {
            return StoragePermissionResult.allowed();
        }

        @Override public StorageOperationResult extractExact(ServerPlayer player,
                StorageItemKey key, long amount, boolean simulate) {
            ItemStack template = key.displayStack();
            ItemStack result = InkFluidSupport.isToken(template)
                    ? InkFluidSupport.extract(network, template, Math.toIntExact(amount), simulate)
                    : RSIntegrationNetwork.extractExactFromNetwork(network, template,
                    Math.toIntExact(amount), player, simulate);
            return StorageOperationResult.extracted(
                    simulate ? StorageOperationMode.SIMULATE
                            : StorageOperationMode.PERFORM,
                    amount, result.isEmpty() ? List.of() : List.of(result));
        }

        @Override public StorageOperationResult extractMatching(ServerPlayer player, Ingredient ingredient,
                                                                 long amount, boolean simulate) {
            if (matchingSession == null) {
                matchingSession = new RefinedStorageBackend()
                        .openSession(network);
            }
            return matchingSession.extractMatching(player, ingredient, amount, simulate);
        }

        @Override public StorageOperationResult insert(ServerPlayer player, ItemStack stack, boolean simulate) {
            ItemStack remainder = InkFluidSupport.isToken(stack) ? InkFluidSupport.insert(network, stack, simulate)
                    : network.insertItem(stack.copy(), stack.getCount(),
                    simulate ? com.refinedmods.refinedstorage.api.util.Action.SIMULATE
                            : com.refinedmods.refinedstorage.api.util.Action.PERFORM);
            if (!simulate) {
                RSIntegrationMod.LOGGER.debug(
                        "[RSI-Storage-RS] insert {} x{} -> remainder x{} at {}",
                        ItemStackUtils.registryId(stack), stack.getCount(), remainder.getCount(),
                        network.getPosition());
            }
            return StorageOperationResult.inserted(
                    simulate ? StorageOperationMode.SIMULATE
                            : StorageOperationMode.PERFORM,
                    stack, remainder);
        }
    }
}
