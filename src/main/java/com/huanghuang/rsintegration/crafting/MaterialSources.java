package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.crafting.CraftingResolver.StackKey;
import com.refinedmods.refinedstorage.api.network.INetwork;
import com.refinedmods.refinedstorage.api.storage.cache.IStorageCache;
import com.refinedmods.refinedstorage.api.util.StackListEntry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.*;

public final class MaterialSources {

    private static final Map<String, Map<StackKey, Integer>> cache = new HashMap<>();
    private static int lastTick = -1;

    private MaterialSources() {}

    /**
     * Count all items in a player's main inventory, keyed by
     * {@link StackKey} (item + NBT tag) for NBT-aware identity.
     */
    public static Map<StackKey, Integer> countInventory(Player player) {
        Map<StackKey, Integer> map = new HashMap<>();
        Inventory inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty()) {
                map.merge(StackKey.of(stack, true), stack.getCount(), Integer::sum);
            }
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
                counts.merge(StackKey.of(stack, true), stack.getCount(), Integer::sum);
            }
        }
    }

    /**
     * Unified enumeration: aggregates RS network + player inventory into a
     * single NBT-aware counts map. Result is cached per-player per-tick to
     * avoid redundant RS storage scans (can be called 2-3 times within a
     * single craft request).
     */
    public static Map<StackKey, Integer> listAllAvailable(ServerPlayer player, @Nullable INetwork network) {
        MinecraftServer server = player.getServer();
        int currentTick = server != null ? server.getTickCount() : 0;
        if (currentTick != lastTick) {
            cache.clear();
            lastTick = currentTick;
        }
        // Include network presence in cache key so results with/without network don't collide
        String cacheKey = player.getUUID() + ":" + (network != null);
        return cache.computeIfAbsent(cacheKey, k -> {
            Map<StackKey, Integer> available = countInventory(player);
            if (network != null) {
                addNetworkItems(available, network);
            }
            return available;
        });
    }

    /** Invalidate cached counts for a player after items are consumed mid-tick. */
    public static void invalidateFor(ServerPlayer player) {
        String withNet = player.getUUID() + ":true";
        String withoutNet = player.getUUID() + ":false";
        cache.remove(withNet);
        cache.remove(withoutNet);
    }

}
