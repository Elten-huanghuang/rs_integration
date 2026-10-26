package com.huanghuang.rsintegration.crafting;
import com.huanghuang.rsintegration.crafting.endpoint.CraftStorageEndpoint;

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
    private static final Object cacheLock = new Object();
    private static volatile int lastTick = -1;
    private static final LogSampler OVERFLOW_LOGS = new LogSampler(60_000L);

    private MaterialSources() {}

    public static long saturatedAdd(long left, long right) {
        if (right <= 0) return left;
        return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }

    /**
     * 将外部或旧版本调用方传入的数量表归一为 Long。
     *
     * <p>数量表的泛型在运行时会被擦除；升级过程中仍可能收到
     * {@link Integer} 值。统一在存储边界转换，避免后续读取时发生
     * {@code Integer cannot be cast to Long}。</p>
     */
    public static Map<StackKey, Long> toLongCounts(Map<?, ?> supplied) {
        if (supplied == null || supplied.isEmpty()) return Map.of();
        Map<StackKey, Long> normalized = new HashMap<>(supplied.size());
        supplied.forEach((rawKey, rawAmount) -> {
            if (!(rawKey instanceof StackKey key) || !(rawAmount instanceof Number number)) return;
            long amount = number.longValue();
            if (amount <= 0L) return;
            normalized.merge(key, amount, MaterialSources::saturatedAdd);
        });
        return Map.copyOf(normalized);
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
    public static Map<StackKey, Long> countInventory(Player player) {
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
    /** Backend-neutral exact availability view shared by every planning caller. */
    public static Map<StackKey, Long> listAllAvailable(
            ServerPlayer player, @Nullable CraftStorageEndpoint endpoint) {
        int currentTick = currentTick(player);
        String cacheKey = cacheKey(player, endpoint);
        rotateCache(currentTick);
        Map<StackKey, Long> cached = cache.get(cacheKey);
        if (cached != null) {
            Map<StackKey, Long> normalized = toLongCounts(cached);
            if (!normalized.equals(cached)) cache.put(cacheKey, normalized);
            return normalized;
        }
        AvailabilityLoad loaded = loadAllAvailable(player, endpoint);
        if (!loaded.snapshotSuccessful()) return loaded.counts();
        Map<StackKey, Long> snapshot = Map.copyOf(loaded.counts());
        cache.putIfAbsent(cacheKey, snapshot);
        return snapshot;
    }

    /**
     * 执行阶段使用的新鲜视图。玩家或后端刚修改库存时，同一 tick 的缓存
     * 不能作为最终下单依据。
     */
    public static Map<StackKey, Long> listAllAvailableFresh(
            ServerPlayer player, @Nullable CraftStorageEndpoint endpoint) {
        return Map.copyOf(loadAllAvailable(player, endpoint).counts());
    }

    private static AvailabilityLoad loadAllAvailable(
            ServerPlayer player, @Nullable CraftStorageEndpoint endpoint) {
        Map<StackKey, Long> available = new HashMap<>(toLongCounts(countInventory(player)));
        if (endpoint == null) {
            return new AvailabilityLoad(available, true);
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
        return new AvailabilityLoad(available, snapshotResult.successful());
    }

    private record AvailabilityLoad(Map<StackKey, Long> counts,
                                    boolean snapshotSuccessful) {}

    /**
     * Fresh narrow snapshot used when a completed plan only needs its concrete
     * initial supplies revalidated. Backends that support typed snapshots avoid
     * rebuilding canonical identities for unrelated network contents.
     */
    public static Map<StackKey, Long> listAvailableForTypes(
            ServerPlayer player, @Nullable CraftStorageEndpoint endpoint,
            Set<Item> itemTypes) {
        if (itemTypes == null || itemTypes.isEmpty()) return Map.of();
        Map<StackKey, Long> available = new HashMap<>(toLongCounts(countInventory(player)));
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
    }

    /**
     * Invalidate a player's view and, when known, only the affected legacy RS
     * network. The broad overload remains for callers that do not know which
     * storage was mutated.
     */
    public static void invalidateFor(ServerPlayer player, @Nullable CraftStorageEndpoint endpoint) {
        String prefix = player.getUUID() + ":";
        cache.keySet().removeIf(key -> key.startsWith(prefix));
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

    private static void rotateCache(int currentTick) {
        if (currentTick == lastTick) return;
        synchronized (cacheLock) {
            if (currentTick != lastTick) {
                cache.clear();
                lastTick = currentTick;
            }
        }
    }

}
