package com.huanghuang.rsintegration.crafting.planning;

import com.huanghuang.rsintegration.crafting.plan.PlanResponse;
import net.minecraft.resources.ResourceLocation;

import java.util.Comparator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Bounded, short-lived preview cache independent from packet transport code. */
public final class PlanCache {
    public static final long DEFAULT_TTL_NANOS = 500_000_000L;
    private static final int MAX_ENTRIES = 64;
    private final long ttlNanos;
    private final ConcurrentHashMap<Key, Entry> entries = new ConcurrentHashMap<>();

    public PlanCache() {
        this(DEFAULT_TTL_NANOS);
    }

    public PlanCache(long ttlNanos) {
        if (ttlNanos <= 0) throw new IllegalArgumentException("ttlNanos");
        this.ttlNanos = ttlNanos;
    }

    public Entry get(Key key, long now) {
        Entry entry = entries.get(key);
        if (entry == null) return null;
        if (now - entry.createdNanos() >= ttlNanos) {
            entries.remove(key, entry);
            return null;
        }
        return entry;
    }

    public void put(Key key, PlanResponse plan, PlanningSnapshot snapshot, long now) {
        entries.put(key, new Entry(plan, snapshot, now));
        prune(now);
    }

    public void clear() {
        entries.clear();
    }

    public void removePlayer(UUID playerId) {
        entries.keySet().removeIf(key -> key.playerId().equals(playerId));
    }

    public int size() {
        return entries.size();
    }

    private void prune(long now) {
        long cutoff = now - ttlNanos;
        entries.entrySet().removeIf(entry -> entry.getValue().createdNanos() < cutoff);
        if (entries.size() <= MAX_ENTRIES) return;
        entries.entrySet().stream()
                .sorted(Map.Entry.comparingByValue(Comparator.comparingLong(Entry::createdNanos)))
                .limit(entries.size() - MAX_ENTRIES)
                .map(Map.Entry::getKey)
                .toList()
                .forEach(entries::remove);
    }

    public record Key(UUID playerId, ResourceLocation recipeId,
                      Map<String, String> forcedRecipes, int repeatCount,
                      String clickedOutputToken) {
        public Key {
            forcedRecipes = Map.copyOf(forcedRecipes);
        }
    }

    public record Entry(PlanResponse plan, PlanningSnapshot snapshot, long createdNanos) {}
}
