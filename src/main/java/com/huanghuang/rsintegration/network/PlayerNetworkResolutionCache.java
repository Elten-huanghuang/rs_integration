package com.huanghuang.rsintegration.network;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Per-player cache with short-lived negative entries and tick-scoped positive entries. */
final class PlayerNetworkResolutionCache<T> {
    private final Map<UUID, Entry<T>> entries = new ConcurrentHashMap<>();
    private final long negativeTtlTicks;

    PlayerNetworkResolutionCache(long negativeTtlTicks) {
        if (negativeTtlTicks < 0) throw new IllegalArgumentException("negative TTL must not be negative");
        this.negativeTtlTicks = negativeTtlTicks;
    }

    @Nullable
    Entry<T> get(UUID playerId, Object server, Object dimension, Object menu, long tick) {
        Entry<T> entry = entries.get(playerId);
        return entry != null && entry.matches(server, dimension, menu, tick, negativeTtlTicks)
                ? entry : null;
    }

    void put(UUID playerId, Object server, Object dimension, Object menu, long tick, @Nullable T value) {
        entries.put(playerId, new Entry<>(server, dimension, menu, tick, value));
    }

    void invalidate(UUID playerId) {
        entries.remove(playerId);
    }

    void clear() {
        entries.clear();
    }

    record Entry<T>(Object server, Object dimension, Object menu, long tick, @Nullable T value) {
        boolean matches(Object currentServer, Object currentDimension, Object currentMenu,
                        long currentTick, long negativeTtlTicks) {
            long age = currentTick - tick;
            return server == currentServer
                    && java.util.Objects.equals(dimension, currentDimension)
                    && menu == currentMenu
                    && age >= 0
                    && (value == null ? age <= negativeTtlTicks : age == 0);
        }
    }
}
