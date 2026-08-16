package com.huanghuang.rsintegration.mods.rs.recentsearch;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class RecentSearchHistory {
    private final int maxStoredEntries;
    private final List<RecentSearchEntry> entries = new ArrayList<>();

    RecentSearchHistory(int maxStoredEntries) {
        if (maxStoredEntries < 1) {
            throw new IllegalArgumentException("maxStoredEntries must be positive");
        }
        this.maxStoredEntries = maxStoredEntries;
    }

    List<RecentSearchEntry> entries() {
        return List.copyOf(entries);
    }

    List<RecentSearchEntry> visibleEntries(int limit) {
        return visibleEntries(limit, true);
    }

    List<RecentSearchEntry> visibleEntries(int limit, boolean favoritesFirst) {
        if (limit <= 0 || entries.isEmpty()) return List.of();
        if (!favoritesFirst) {
            return List.copyOf(entries.subList(0, Math.min(limit, entries.size())));
        }
        List<RecentSearchEntry> visible = new ArrayList<>(Math.min(limit, entries.size()));
        appendGroup(visible, true, limit);
        appendGroup(visible, false, limit);
        return List.copyOf(visible);
    }

    boolean replaceAll(List<RecentSearchEntry> loadedEntries) {
        List<RecentSearchEntry> before = List.copyOf(entries);
        entries.clear();

        Map<String, RecentSearchEntry> unique = new LinkedHashMap<>();
        for (RecentSearchEntry entry : loadedEntries) {
            String query = normalize(entry.query());
            if (query == null) continue;
            RecentSearchEntry previous = unique.get(query);
            boolean favorite = entry.favorite() || previous != null && previous.favorite();
            unique.putIfAbsent(query, new RecentSearchEntry(query, favorite));
            if (previous != null && favorite != previous.favorite()) {
                unique.put(query, new RecentSearchEntry(query, true));
            }
        }
        entries.addAll(unique.values());
        enforceCapacity();
        return !entries.equals(before);
    }

    boolean record(String rawQuery) {
        String query = normalize(rawQuery);
        if (query == null) return false;

        List<RecentSearchEntry> before = List.copyOf(entries);
        boolean favorite = false;
        for (int index = 0; index < entries.size(); index++) {
            RecentSearchEntry entry = entries.get(index);
            if (entry.query().equals(query)) {
                favorite = entry.favorite();
                entries.remove(index);
                break;
            }
        }
        entries.add(0, new RecentSearchEntry(query, favorite));
        enforceCapacity();
        return !entries.equals(before);
    }

    boolean toggleFavorite(String rawQuery) {
        String query = normalize(rawQuery);
        if (query == null) return false;

        for (int index = 0; index < entries.size(); index++) {
            RecentSearchEntry entry = entries.get(index);
            if (!entry.query().equals(query)) continue;
            entries.remove(index);
            entries.add(0, new RecentSearchEntry(query, !entry.favorite()));
            return true;
        }

        entries.add(0, new RecentSearchEntry(query, true));
        enforceCapacity();
        return true;
    }

    boolean isFavorite(String rawQuery) {
        String query = normalize(rawQuery);
        if (query == null) return false;
        for (RecentSearchEntry entry : entries) {
            if (entry.query().equals(query)) return entry.favorite();
        }
        return false;
    }

    boolean remove(String query) {
        return entries.removeIf(entry -> entry.query().equals(query));
    }

    boolean clear() {
        if (entries.isEmpty()) return false;
        entries.clear();
        return true;
    }

    private void appendGroup(List<RecentSearchEntry> target, boolean favorite, int limit) {
        if (target.size() >= limit) return;
        for (RecentSearchEntry entry : entries) {
            if (entry.favorite() == favorite) {
                target.add(entry);
                if (target.size() >= limit) return;
            }
        }
    }

    private void enforceCapacity() {
        while (entries.size() > maxStoredEntries) {
            int victim = -1;
            for (int index = entries.size() - 1; index >= 0; index--) {
                if (!entries.get(index).favorite()) {
                    victim = index;
                    break;
                }
            }
            entries.remove(victim >= 0 ? victim : entries.size() - 1);
        }
    }

    private static String normalize(String query) {
        if (query == null) return null;
        String normalized = query.trim();
        return normalized.isEmpty() || normalized.length() > RecentSearchStore.MAX_QUERY_CHARS
                ? null : normalized;
    }
}
