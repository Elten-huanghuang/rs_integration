package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.util.LogSampler;
import com.huanghuang.rsintegration.crafting.CraftingResolver.StackKey;
import com.huanghuang.rsintegration.storage.StorageSnapshotResult;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraft.world.item.Items;
import net.minecraftforge.fml.ModList;

import javax.annotation.Nullable;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class MaterialSources {

    private static final Map<String, Map<StackKey, Long>> cache = new ConcurrentHashMap<>();
    private static final Map<String, Map<StackKey, Long>> networkCache = new ConcurrentHashMap<>();
    private static final Object cacheLock = new Object();
    private static volatile int lastTick = -1;
    private static final LogSampler OVERFLOW_LOGS = new LogSampler(60_000L);

    private MaterialSources() {}

    static long saturatedAdd(long left, long right) {
        if (right <= 0) return left;
        return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }

    static int saturatedAdd(int left, int right) {
        if (right <= 0) return left;
        return left > Integer.MAX_VALUE - right ? Integer.MAX_VALUE : left + right;
    }

    private static void mergeAvailable(Map<StackKey, Long> counts, StackKey key, long amount,
                                       String source) {
        if (amount <= 0) {
            if (amount < 0 && OVERFLOW_LOGS.allow("negative:" + key.item())) {
                RSIntegrationMod.LOGGER.warn(
                        "[RSI-Materials] rejected negative external quantity: source={} item={} amount={} nbt={}",
                        source, ForgeRegistries.ITEMS.getKey(key.item()), amount, key.tag());
            }
            return;
        }
        long before = counts.getOrDefault(key, 0L);
        long after = saturatedAdd(before, amount);
        counts.put(key, after);
        if (after == Long.MAX_VALUE
                && OVERFLOW_LOGS.allow("saturated:" + key.item())) {
            RSIntegrationMod.LOGGER.warn(
                    "[RSI-Materials] quantity saturated for planning: source={} item={} amount={} existing={} planningCount={} limit={}",
                    source, ForgeRegistries.ITEMS.getKey(key.item()), amount, before, after,
                    Long.MAX_VALUE);
        }
    }

    /**
     * Count all items in a player's main inventory, keyed by
     * {@link StackKey} (item + NBT tag) for NBT-aware identity.
     * Only counts the main inventory (36 slots) — not armor or offhand,
     */
    public static Map<StackKey, Integer> countInventory(Player player) {
        return intCounts(countInventoryLong(player));
    }

    public static Map<StackKey, Long> countInventoryLong(Player player) {
        Map<StackKey, Long> map = new HashMap<>();
        Inventory inv = player.getInventory();
        for (ItemStack stack : inv.items) {
            if (!stack.isEmpty()) {
                mergeAvailable(map, StackKey.of(stack, true), stack.getCount(), "player_inventory");
            }
        }
        // Count backpack contents (including backpacks in curio slots)
        if (player instanceof ServerPlayer sp && ModList.get().isLoaded("sophisticatedbackpacks")) {
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
     * Unified enumeration: aggregates RS network + player inventory into a
     * single NBT-aware counts map. Result is cached per-player per-tick to
     * avoid redundant RS storage scans (can be called 2-3 times within a
     * single craft request).
     *
     * <p>Cache lookup is lock-free; tick advancement uses double-checked
     * locking. Expensive network scans are performed outside any lock.</p>
     */
    /** Backend-neutral availability view used by migrated planning callers. */
    public static Map<StackKey, Integer> listAllAvailable(ServerPlayer player,
                                                           @Nullable CraftStorageEndpoint endpoint) {
        return intCounts(listAllAvailableLong(player, endpoint));
    }

    public static Map<StackKey, Long> listAllAvailableLong(
            ServerPlayer player, @Nullable CraftStorageEndpoint endpoint) {
        int currentTick = currentTick(player);
        String cacheKey = cacheKey(player, endpoint);
        rotateCache(currentTick);
        Map<StackKey, Long> cached = cache.get(cacheKey);
        if (cached != null) return cached;

        Map<StackKey, Long> available = countInventoryLong(player);
        if (endpoint == null) {
            Map<StackKey, Long> snapshot = Map.copyOf(available);
            cache.putIfAbsent(cacheKey, snapshot);
            return snapshot;
        }
        // Keep the RS compatibility bridge on its native cache representation.
        // StorageItemKey intentionally normalizes display stacks to count=1 and
        // can lose backend-specific payload details; the old planner consumed
        // StackListEntry directly and is authoritative for RS item identity.
        if (endpoint instanceof LegacyRsCraftStorageEndpoint legacy) {
            String networkKey = networkCacheKey(legacy);
            Map<StackKey, Long> networkItems = networkCache.get(networkKey);
            if (networkItems == null) {
                Map<StackKey, Long> scanned = new HashMap<>();
                for (ItemStack stack : legacy.cachedStackVariants()) {
                    if (!stack.isEmpty()) {
                        mergeAvailable(scanned, StackKey.of(stack, true), stack.getCount(),
                                "refined_storage");
                    }
                }
                networkItems = Map.copyOf(scanned);
                Map<StackKey, Long> existing = networkCache.putIfAbsent(networkKey, networkItems);
                if (existing != null) networkItems = existing;
            }
            networkItems.forEach((key, count) -> mergeAvailable(available, key, count, "refined_storage_cache"));
            Map<StackKey, Long> snapshot = Map.copyOf(available);
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
        for (ItemStack filled : List.of(new ItemStack(Items.WATER_BUCKET),
                new ItemStack(Items.LAVA_BUCKET))) {
            long derived = endpoint.session().countDerivedContainer(player, filled);
            if (derived > 0) {
                mergeAvailable(available, StackKey.of(filled, true), derived, "derived_container");
            }
        }
        if (RSIntegrationMod.LOGGER.isDebugEnabled()) {
            long exact = 0L;
            for (var entry : available.entrySet()) {
                var id = ForgeRegistries.ITEMS.getKey(entry.getKey().item());
                if (id != null && (id.toString().equals("confluence:demon_heart")
                        || id.toString().equals("terra_curio:demon_heart"))) {
                    exact = saturatedAdd(exact, entry.getValue());
                }
            }
            RSIntegrationMod.LOGGER.debug(
                    "[RSI-Materials] snapshot status={} storedItems={} availableKeys={} demon_heart={}",
                    snapshotResult.status(),
                    snapshotResult.snapshot().map(s -> s.items().size()).orElse(0),
                    available.size(), exact);
        }
        // A failed backend snapshot must not be cached as an inventory-only
        // result: a transient backend failure can recover within this tick.
        if (!snapshotResult.successful()) return available;
        Map<StackKey, Long> snapshot = Map.copyOf(available);
        cache.putIfAbsent(cacheKey, snapshot);
        return snapshot;
    }

    private static Map<StackKey, Integer> intCounts(Map<StackKey, Long> precise) {
        Map<StackKey, Integer> compatible = new HashMap<>(precise.size());
        precise.forEach((key, count) -> compatible.put(key,
                (int) Math.min(Integer.MAX_VALUE, Math.max(0L, count))));
        return Map.copyOf(compatible);
    }

    /**
     * Fresh narrow snapshot used when a completed plan only needs its concrete
     * initial supplies revalidated. Backends that support typed snapshots avoid
     * rebuilding canonical identities for unrelated network contents.
     */
    public static Map<StackKey, Integer> listAvailableForTypes(
            ServerPlayer player, @Nullable CraftStorageEndpoint endpoint,
            Set<Item> itemTypes) {
        return intCounts(listAvailableForTypesLong(player, endpoint, itemTypes));
    }

    public static Map<StackKey, Long> listAvailableForTypesLong(
            ServerPlayer player, @Nullable CraftStorageEndpoint endpoint,
            Set<Item> itemTypes) {
        if (itemTypes == null || itemTypes.isEmpty()) return Map.of();
        Map<StackKey, Long> available = countInventoryLong(player);
        available.entrySet().removeIf(entry -> !itemTypes.contains(entry.getKey().item()));
        if (endpoint == null) return Map.copyOf(available);

        StorageSnapshotResult snapshotResult = endpoint.snapshot(player, Set.copyOf(itemTypes));
        snapshotResult.snapshot().ifPresent(snapshot -> snapshot.items().forEach(item -> {
            ItemStack stack = item.stack();
            if (!stack.isEmpty() && itemTypes.contains(stack.getItem())) {
                mergeAvailable(available, StackKey.of(stack, true), item.amount(),
                        "revalidation_snapshot");
            }
        }));
        for (ItemStack filled : List.of(new ItemStack(Items.WATER_BUCKET),
                new ItemStack(Items.LAVA_BUCKET))) {
            if (!itemTypes.contains(filled.getItem())) continue;
            long derived = endpoint.session().countDerivedContainer(player, filled);
            if (derived > 0) {
                mergeAvailable(available, StackKey.of(filled, true), derived,
                        "revalidation_derived_container");
            }
        }
        return Map.copyOf(available);
    }

    /** Invalidate cached counts for a player after items are consumed mid-tick. */
    public static void invalidateFor(ServerPlayer player) {
        String prefix = player.getUUID() + ":";
        cache.keySet().removeIf(key -> key.startsWith(prefix));
        networkCache.clear();
    }

    /**
     * Invalidate a player's view and, when known, only the affected legacy RS
     * network. The broad overload remains for callers that do not know which
     * storage was mutated.
     */
    public static void invalidateFor(ServerPlayer player, @Nullable CraftStorageEndpoint endpoint) {
        String prefix = player.getUUID() + ":";
        cache.keySet().removeIf(key -> key.startsWith(prefix));
        if (endpoint == null) {
            // Legacy callers may still mutate an RS network without exposing
            // the endpoint identity. Keep the old correctness guarantee.
            networkCache.clear();
            return;
        }
        if (endpoint instanceof LegacyRsCraftStorageEndpoint legacy) {
            networkCache.remove(networkCacheKey(legacy));
        }
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
        return endpoint.cacheKey();
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
