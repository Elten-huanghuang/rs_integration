package com.huanghuang.rsintegration.storage;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.Ingredient;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Immutable point-in-time view of the item identities visible in a session. */
public final class StorageSnapshot {
    public static final long UNKNOWN_REVISION = -1;

    private final StorageBackendId backendId;
    private final List<StoredItem> items;
    private final Map<StorageItemKey, StoredItem> exactItems;
    private final Map<StorageItemKey, Integer> itemOrdinals;
    private final Map<Item, List<StoredItem>> itemsByType;
    private final long revision;
    private static final int CANDIDATE_CACHE_WEIGHT = 4096;
    private com.google.common.cache.Cache<Set<Item>, List<StoredItem>> candidateCache;

    public enum MatchStatus { SUCCESS, EMPTY_INGREDIENT, FAILED }

    public record MatchResult(MatchStatus status, List<StoredItem> items,
                              StorageDiagnosticCode diagnosticCode) {
        public MatchResult {
            Objects.requireNonNull(status, "status");
            items = List.copyOf(Objects.requireNonNull(items, "items"));
            Objects.requireNonNull(diagnosticCode, "diagnosticCode");
            if (status == MatchStatus.SUCCESS) {
                if (diagnosticCode != StorageDiagnosticCode.NONE) {
                    throw new IllegalArgumentException("successful match cannot carry a diagnostic");
                }
            } else if (!items.isEmpty()) {
                throw new IllegalArgumentException("unsuccessful match cannot carry items");
            } else if ((status == MatchStatus.FAILED)
                    != (diagnosticCode != StorageDiagnosticCode.NONE)) {
                throw new IllegalArgumentException("failed match requires a diagnostic");
            }
        }

        public boolean successful() { return status == MatchStatus.SUCCESS; }
    }

    public StorageSnapshot(StorageBackendId backendId, List<StoredItem> items) {
        this(backendId, items, UNKNOWN_REVISION);
    }

    public StorageSnapshot(StorageBackendId backendId, List<StoredItem> items, long revision) {
        this.backendId = Objects.requireNonNull(backendId, "backendId");
        Objects.requireNonNull(items, "items");
        if (revision < UNKNOWN_REVISION) throw new IllegalArgumentException("invalid snapshot revision");
        Map<StorageItemKey, Long> amounts = new LinkedHashMap<>();
        for (StoredItem item : items) {
            Objects.requireNonNull(item, "item");
            if (!backendId.equals(item.key().backendId())) {
                throw new IllegalArgumentException("snapshot contains an item from another backend");
            }
            amounts.merge(item.key(), item.amount(), StorageSnapshot::saturatedAdd);
        }
        List<StoredItem> normalized = new ArrayList<>(amounts.size());
        for (Map.Entry<StorageItemKey, Long> entry : amounts.entrySet()) {
            normalized.add(new StoredItem(entry.getKey(), entry.getValue()));
        }
        this.items = List.copyOf(normalized);
        Map<StorageItemKey, StoredItem> exact = new HashMap<>(normalized.size());
        Map<StorageItemKey, Integer> ordinals = new HashMap<>(normalized.size());
        Map<Item, List<StoredItem>> byType = new HashMap<>();
        for (int ordinal = 0; ordinal < normalized.size(); ordinal++) {
            StoredItem item = normalized.get(ordinal);
            exact.put(item.key(), item);
            ordinals.put(item.key(), ordinal);
            byType.computeIfAbsent(item.key().displayItem(), ignored -> new ArrayList<>())
                    .add(item);
        }
        this.exactItems = Map.copyOf(exact);
        this.itemOrdinals = Map.copyOf(ordinals);
        byType.replaceAll((ignored, stored) -> List.copyOf(stored));
        this.itemsByType = Map.copyOf(byType);
        this.revision = revision;
    }

    public StorageBackendId backendId() { return backendId; }

    public List<StoredItem> items() {
        return items;
    }

    public long revision() { return revision; }

    public long countExact(StorageItemKey key) {
        Objects.requireNonNull(key, "key");
        StoredItem item = exactItems.get(key);
        return item == null ? 0L : item.amount();
    }

    public MatchResult match(Ingredient ingredient) {
        Objects.requireNonNull(ingredient, "ingredient");
        try {
            if (ingredient.isEmpty()) {
                return new MatchResult(MatchStatus.EMPTY_INGREDIENT, List.of(),
                        StorageDiagnosticCode.NONE);
            }
            // Vanilla Ingredient is item-only. Its complete candidate list is
            // already the match result; NBT/custom predicates still get copies.
            if (ingredient.getClass() == Ingredient.class) {
                return new MatchResult(MatchStatus.SUCCESS, candidates(ingredient), StorageDiagnosticCode.NONE);
            }
            List<StoredItem> matches = new ArrayList<>();
            for (StoredItem item : candidates(ingredient)) {
                if (com.huanghuang.rsintegration.crafting.IngredientMatcher.test(ingredient, item.stack())) {
                    matches.add(item);
                }
            }
            return new MatchResult(MatchStatus.SUCCESS, matches, StorageDiagnosticCode.NONE);
        } catch (RuntimeException | LinkageError e) {
            return new MatchResult(MatchStatus.FAILED, List.of(),
                    StorageDiagnosticCode.INGREDIENT_MATCH_FAILED);
        }
    }

    private List<StoredItem> candidates(Ingredient ingredient) {
        Set<Item> requestedTypes = com.huanghuang.rsintegration.crafting.IngredientMatcher
                .itemTypesForMatching(ingredient);
        if (requestedTypes == null) {
            return items;
        }
        if (requestedTypes.isEmpty()) return List.of();
        if (requestedTypes.size() == 1) {
            return itemsByType.getOrDefault(requestedTypes.iterator().next(), List.of());
        }
        var cache = candidateCache();
        List<StoredItem> cached = cache.getIfPresent(requestedTypes);
        if (cached != null) return cached;
        List<StoredItem> selected = new ArrayList<>();
        for (Item type : requestedTypes) {
            selected.addAll(itemsByType.getOrDefault(type, List.of()));
        }
        selected.sort(Comparator.comparingInt(item -> itemOrdinals.get(item.key())));
        List<StoredItem> result = List.copyOf(selected);
        // Cache only immutable candidates, never the outcome of an NBT predicate.
        if (1L + requestedTypes.size() + result.size() <= CANDIDATE_CACHE_WEIGHT) {
            cache.put(requestedTypes, result);
        }
        return result;
    }

    private synchronized com.google.common.cache.Cache<Set<Item>, List<StoredItem>> candidateCache() {
        if (candidateCache == null) {
            candidateCache = com.google.common.cache.CacheBuilder.newBuilder()
                    .concurrencyLevel(1).maximumWeight(CANDIDATE_CACHE_WEIGHT)
                    .weigher((Set<Item> types, List<StoredItem> matches) -> 1 + types.size() + matches.size())
                    .build();
        }
        return candidateCache;
    }

    private static long saturatedAdd(long left, long right) {
        return Long.MAX_VALUE - left < right ? Long.MAX_VALUE : left + right;
    }
}
