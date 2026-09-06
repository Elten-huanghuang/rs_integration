package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.util.LogSampler;
import com.huanghuang.rsintegration.crafting.CraftingResolver.StackKey;
import com.refinedmods.refinedstorage.api.network.INetwork;
import com.refinedmods.refinedstorage.api.storage.cache.IStorageCache;
import com.refinedmods.refinedstorage.api.util.StackListEntry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nullable;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class MaterialSources {

    private static final Map<String, Map<StackKey, Integer>> cache = new ConcurrentHashMap<>();
    private static final Map<String, Map<StackKey, Integer>> networkCache = new ConcurrentHashMap<>();
    private static final Object cacheLock = new Object();
    private static volatile int lastTick = -1;
    private static final LogSampler OVERFLOW_LOGS = new LogSampler(60_000L);

    private MaterialSources() {}

    static int saturatedAdd(int left, int right) {
        if (right <= 0) return left;
        return left > Integer.MAX_VALUE - right ? Integer.MAX_VALUE : left + right;
    }

    private static void mergeAvailable(Map<StackKey, Integer> counts, StackKey key, long amount,
                                       String source) {
        if (amount <= 0) {
            if (amount < 0 && OVERFLOW_LOGS.allow("negative:" + key.item())) {
                RSIntegrationMod.LOGGER.warn(
                        "[RSI-Materials] rejected negative external quantity: source={} item={} amount={} nbt={}",
                        source, ForgeRegistries.ITEMS.getKey(key.item()), amount, key.tag());
            }
            return;
        }
        int before = counts.getOrDefault(key, 0);
        int added = (int) Math.min(Integer.MAX_VALUE, amount);
        int after = saturatedAdd(before, added);
        counts.put(key, after);
        if ((amount > Integer.MAX_VALUE || after == Integer.MAX_VALUE)
                && OVERFLOW_LOGS.allow("saturated:" + key.item())) {
            RSIntegrationMod.LOGGER.warn(
                    "[RSI-Materials] quantity saturated for planning: source={} item={} amount={} existing={} planningCount={} limit={}",
                    source, ForgeRegistries.ITEMS.getKey(key.item()), amount, before, after,
                    Integer.MAX_VALUE);
        }
    }

    /**
     * Count all items in a player's main inventory, keyed by
     * {@link StackKey} (item + NBT tag) for NBT-aware identity.
     * Only counts the main inventory (36 slots) — not armor or offhand,
     */
    public static Map<StackKey, Integer> countInventory(Player player) {
        Map<StackKey, Integer> map = new HashMap<>();
        Inventory inv = player.getInventory();
        for (ItemStack stack : inv.items) {
            if (!stack.isEmpty()) {
                mergeAvailable(map, StackKey.of(stack, true), stack.getCount(), "player_inventory");
            }
        }
        // Count backpack contents (including backpacks in curio slots)
        if (player instanceof ServerPlayer sp && net.minecraftforge.fml.ModList.get().isLoaded("sophisticatedbackpacks")) {
            try {
                for (var bp : ExtractionLedger
                        .findAllBackpackInventories(sp)) {
                    for (int i = 0; i < bp.getSlots(); i++) {
                        ItemStack stack = bp.getStackInSlot(i);
                        if (!stack.isEmpty()
                                && !InventoryProtectionPolicy.isProtectedBackpackItem(stack)) {
                            mergeAvailable(map, StackKey.of(stack, true), stack.getCount(), "backpack");
                        }
                    }
                }
            } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI] backpack inventory scan failed", e); }
        }
        return map;
    }

    /**
     * Add all items from an RS network into the NBT-aware counts map.
     */
    public static void addNetworkItems(Map<StackKey, Integer> counts, @Nullable INetwork network) {
        if (network == null) return;
        IStorageCache<ItemStack> cache = network.getItemStorageCache();
        if (cache == null) return;

        List<StackListEntry<ItemStack>> entries = new ArrayList<>(cache.getList().getStacks());
        for (StackListEntry<ItemStack> entry : entries) {
            ItemStack stack = entry.getStack();
            if (!stack.isEmpty()) {
                mergeAvailable(counts, StackKey.of(stack, true), stack.getCount(), "refined_storage");
            }
        }
    }

    /**
     * Unified enumeration: aggregates RS network + player inventory into a
     * single NBT-aware counts map. Result is cached per-player per-tick to
     * avoid redundant RS storage scans (can be called 2-3 times within a
     * single craft request).
     *
     * <p>Cache lookup is lock-free; tick advancement uses double-checked
     * locking. Expensive network scans are performed outside any lock.</p>
     */
    public static Map<StackKey, Integer> listAllAvailable(ServerPlayer player, @Nullable INetwork network) {
        if (network != null) {
            return listAllAvailable(player, CraftStorageEndpoints.fromLegacyNetwork(network));
        }
        int currentTick = currentTick(player);
        String cacheKey = cacheKey(player, null);
        rotateCache(currentTick);
        Map<StackKey, Integer> cached = cache.get(cacheKey);
        if (cached != null) return cached;

        Map<StackKey, Integer> available = countInventory(player);
        Map<StackKey, Integer> snapshot = Map.copyOf(available);
        cache.putIfAbsent(cacheKey, snapshot);
        return snapshot;
    }

    /** Backend-neutral availability view used by migrated planning callers. */
    public static Map<StackKey, Integer> listAllAvailable(ServerPlayer player,
                                                           CraftStorageEndpoint endpoint) {
        int currentTick = currentTick(player);
        String cacheKey = cacheKey(player, endpoint);
        rotateCache(currentTick);
        Map<StackKey, Integer> cached = cache.get(cacheKey);
        if (cached != null) return cached;

        Map<StackKey, Integer> available = countInventory(player);
        // Keep the RS compatibility bridge on its native cache representation.
        // StorageItemKey intentionally normalizes display stacks to count=1 and
        // can lose backend-specific payload details; the old planner consumed
        // StackListEntry directly and is authoritative for RS item identity.
        if (endpoint instanceof LegacyRsCraftStorageEndpoint legacy) {
            String networkKey = networkCacheKey(legacy);
            Map<StackKey, Integer> networkItems = networkCache.get(networkKey);
            if (networkItems == null) {
                Map<StackKey, Integer> scanned = new HashMap<>();
                addNetworkItems(scanned, legacy.network());
                networkItems = Map.copyOf(scanned);
                Map<StackKey, Integer> existing = networkCache.putIfAbsent(networkKey, networkItems);
                if (existing != null) networkItems = existing;
            }
            networkItems.forEach((key, count) -> mergeAvailable(available, key, count, "refined_storage_cache"));
            Map<StackKey, Integer> snapshot = Map.copyOf(available);
            cache.putIfAbsent(cacheKey, snapshot);
            return snapshot;
        }
        var snapshotResult = endpoint.snapshot(player);
        snapshotResult.snapshot().ifPresent(snapshot -> snapshot.items().forEach(item ->
                mergeAvailable(available, StackKey.of(item.stack(), true), item.amount(),
                        "storage_snapshot")));
        // A typed-fluid backend may be able to manufacture vanilla fluid
        // containers from stored empty buckets + fluid.  Include this derived
        // availability in the immutable planning view; the ledger performs
        // the authoritative two-resource extraction during commit.
        for (ItemStack filled : List.of(new ItemStack(net.minecraft.world.item.Items.WATER_BUCKET),
                new ItemStack(net.minecraft.world.item.Items.LAVA_BUCKET))) {
            long derived = endpoint.session().countDerivedContainer(player, filled);
            if (derived > 0) {
                mergeAvailable(available, StackKey.of(filled, true), derived, "derived_container");
            }
        }
        if (RSIntegrationMod.LOGGER.isDebugEnabled()) {
            int exact = 0;
            for (var entry : available.entrySet()) {
                var id = ForgeRegistries.ITEMS.getKey(entry.getKey().item());
                if (id != null && id.toString().equals("confluence:demon_heart")) {
                    exact += entry.getValue();
                }
            }
            RSIntegrationMod.LOGGER.debug(
                    "[RSI-Materials] snapshot status={} storedItems={} availableKeys={} confluence:demon_heart={}",
                    snapshotResult.status(),
                    snapshotResult.snapshot().map(s -> s.items().size()).orElse(0),
                    available.size(), exact);
        }
        // A failed backend snapshot must not be cached as an inventory-only
        // result: a transient backend failure can recover within this tick.
        if (!snapshotResult.successful()) return available;
        Map<StackKey, Integer> snapshot = Map.copyOf(available);
        cache.putIfAbsent(cacheKey, snapshot);
        return snapshot;
    }

    /** Invalidate cached counts for a player after items are consumed mid-tick. */
    public static void invalidateFor(ServerPlayer player) {
        String prefix = player.getUUID() + ":";
        cache.keySet().removeIf(key -> key.startsWith(prefix));
        networkCache.clear();
    }

    private static int currentTick(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        return server != null ? server.getTickCount() : 0;
    }

    private static String cacheKey(ServerPlayer player, @Nullable CraftStorageEndpoint endpoint) {
        if (endpoint == null) return player.getUUID() + ":inventory";
        try {
            return player.getUUID() + ":" + endpoint.session().reference();
        } catch (RuntimeException ignored) {
            // A malformed/disconnected endpoint should remain usable as a
            // non-cached view rather than taking down the craft request.
            return player.getUUID() + ":endpoint@" + System.identityHashCode(endpoint);
        }
    }

    private static String networkCacheKey(LegacyRsCraftStorageEndpoint endpoint) {
        try {
            return endpoint.session().reference().toString();
        } catch (RuntimeException ignored) {
            return "legacy@" + System.identityHashCode(endpoint.network());
        }
    }

    private static void rotateCache(int currentTick) {
        if (currentTick == lastTick) return;
        synchronized (cacheLock) {
            if (currentTick != lastTick) {
                cache.clear();
                networkCache.clear();
                lastTick = currentTick;
            }
        }
    }

}
