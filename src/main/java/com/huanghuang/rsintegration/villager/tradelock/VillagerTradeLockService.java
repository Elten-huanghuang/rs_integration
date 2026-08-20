package com.huanghuang.rsintegration.villager.tradelock;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Server-authoritative, per-player JEI item bookmarks used to guard trade rerolls. */
public final class VillagerTradeLockService {
    public static final int MAX_BOOKMARKS = 4096;

    private static final Map<UUID, BookmarkIndex> BOOKMARKS = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> LAST_NOTICE = new ConcurrentHashMap<>();

    private VillagerTradeLockService() {}

    public static void replace(ServerPlayer player, List<ItemStack> stacks) {
        if (player == null) return;
        BOOKMARKS.put(player.getUUID(), BookmarkIndex.create(stacks));
    }

    public static boolean shouldBlock(ServerPlayer player) {
        if (player == null) return false;
        BookmarkIndex index = BOOKMARKS.get(player.getUUID());
        if (index == null || index.isEmpty()) return false;
        if (!(player.containerMenu instanceof net.minecraft.world.inventory.MerchantMenu menu)) return false;
        return containsBookmarkedResult(index, menu.getOffers());
    }

    static boolean containsBookmarkedResult(BookmarkIndex index, MerchantOffers offers) {
        if (index == null || index.isEmpty() || offers == null || offers.isEmpty()) return false;
        for (MerchantOffer offer : offers) {
            if (offer != null && index.contains(offer.getResult())) return true;
        }
        return false;
    }

    public static boolean shouldNotify(ServerPlayer player, long nowMillis) {
        UUID playerId = player.getUUID();
        Long previous = LAST_NOTICE.put(playerId, nowMillis);
        return previous == null || nowMillis - previous >= 1000L;
    }

    public static void remove(UUID playerId) {
        BOOKMARKS.remove(playerId);
        LAST_NOTICE.remove(playerId);
    }

    public static void clear() {
        BOOKMARKS.clear();
        LAST_NOTICE.clear();
    }

    /** Immutable-by-convention index. Lookup performs no copying or serialization. */
    static final class BookmarkIndex {
        private final Map<Item, Set<CompoundTag>> tagsByItem;

        private BookmarkIndex(Map<Item, Set<CompoundTag>> tagsByItem) {
            this.tagsByItem = tagsByItem;
        }

        static BookmarkIndex create(List<ItemStack> stacks) {
            Map<Item, Set<CompoundTag>> index = new HashMap<>();
            int limit = Math.min(stacks == null ? 0 : stacks.size(), MAX_BOOKMARKS);
            for (int i = 0; i < limit; i++) {
                ItemStack stack = stacks.get(i);
                if (stack == null || stack.isEmpty()) continue;
                index.computeIfAbsent(stack.getItem(), ignored -> new HashSet<>())
                        .add(stack.hasTag() ? stack.getTag().copy() : null);
            }
            return new BookmarkIndex(index);
        }

        boolean contains(ItemStack stack) {
            if (stack == null || stack.isEmpty()) return false;
            Set<CompoundTag> tags = tagsByItem.get(stack.getItem());
            return tags != null && tags.contains(stack.getTag());
        }

        boolean isEmpty() {
            return tagsByItem.isEmpty();
        }
    }
}
