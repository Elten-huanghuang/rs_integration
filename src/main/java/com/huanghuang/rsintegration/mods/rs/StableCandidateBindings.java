package com.huanghuang.rsintegration.mods.rs;

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Maps stable search identities to the transient IDs used by the current RS view. */
final class StableCandidateBindings {
    record Replacement(Set<String> removedKeys, Set<String> addedKeys,
                       int oldKeys, int newKeys, int retainedKeys) {}

    private final Map<UUID, String> keysById = new HashMap<>();
    private final Map<String, Set<UUID>> idsByKey = new HashMap<>();

    @Nullable
    String key(UUID id) {
        return keysById.get(id);
    }

    Set<UUID> ids(String key) {
        Set<UUID> ids = idsByKey.get(key);
        return ids == null ? Set.of() : Set.copyOf(ids);
    }

    void appendIds(String key, Collection<UUID> destination) {
        Set<UUID> ids = idsByKey.get(key);
        if (ids != null) destination.addAll(ids);
    }

    Replacement replace(Map<UUID, String> replacements) {
        Set<String> oldKeys = new HashSet<>(idsByKey.keySet());
        clear();
        for (Map.Entry<UUID, String> replacement : replacements.entrySet()) {
            put(replacement.getKey(), replacement.getValue());
        }
        Set<String> removedKeys = new HashSet<>(oldKeys);
        removedKeys.removeAll(idsByKey.keySet());
        Set<String> addedKeys = new HashSet<>(idsByKey.keySet());
        addedKeys.removeAll(oldKeys);
        int retainedKeys = idsByKey.size() - addedKeys.size();
        return new Replacement(Set.copyOf(removedKeys), Set.copyOf(addedKeys),
                oldKeys.size(), idsByKey.size(), retainedKeys);
    }

    @Nullable
    String put(UUID id, String key) {
        String previous = keysById.get(id);
        if (key.equals(previous)) {
            idsByKey.computeIfAbsent(key, ignored -> new HashSet<>()).add(id);
            return null;
        }
        String orphaned = remove(id);
        keysById.put(id, key);
        idsByKey.computeIfAbsent(key, ignored -> new HashSet<>()).add(id);
        return orphaned;
    }

    @Nullable
    String remove(UUID id) {
        String key = keysById.remove(id);
        if (key == null) return null;
        Set<UUID> ids = idsByKey.get(key);
        if (ids == null) return null;
        ids.remove(id);
        if (!ids.isEmpty()) return null;
        idsByKey.remove(key);
        return key;
    }

    void clear() {
        keysById.clear();
        idsByKey.clear();
    }
}
