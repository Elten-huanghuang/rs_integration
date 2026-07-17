package com.huanghuang.rsintegration.network;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class PlayerNetworkResolutionCacheTest {

    @Test
    void cachesPositiveAndNegativeResultsForSameTickAndContext() {
        PlayerNetworkResolutionCache<Object> cache = new PlayerNetworkResolutionCache<>();
        UUID player = UUID.randomUUID();
        Object server = new Object();
        Object dimension = "overworld";
        Object menu = new Object();
        Object network = new Object();

        cache.put(player, server, dimension, menu, 10, network);
        assertSame(network, cache.get(player, server, dimension, menu, 10).value());

        UUID negativePlayer = UUID.randomUUID();
        cache.put(negativePlayer, server, dimension, menu, 10, null);
        assertNull(cache.get(negativePlayer, server, dimension, menu, 10).value());
    }

    @Test
    void rejectsDifferentTickServerDimensionOrMenu() {
        PlayerNetworkResolutionCache<Object> cache = new PlayerNetworkResolutionCache<>();
        UUID player = UUID.randomUUID();
        Object server = new Object();
        Object menu = new Object();
        cache.put(player, server, "overworld", menu, 10, new Object());

        assertNull(cache.get(player, server, "overworld", menu, 11));
        assertNull(cache.get(player, new Object(), "overworld", menu, 10));
        assertNull(cache.get(player, server, "nether", menu, 10));
        assertNull(cache.get(player, server, "overworld", new Object(), 10));
    }

    @Test
    void explicitInvalidationAndClearRemoveEntries() {
        PlayerNetworkResolutionCache<Object> cache = new PlayerNetworkResolutionCache<>();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        Object server = new Object();
        Object menu = new Object();
        cache.put(first, server, "overworld", menu, 1, new Object());
        cache.put(second, server, "overworld", menu, 1, new Object());

        cache.invalidate(first);
        assertNull(cache.get(first, server, "overworld", menu, 1));
        assertEquals(1, cache.get(second, server, "overworld", menu, 1).tick());

        cache.clear();
        assertNull(cache.get(second, server, "overworld", menu, 1));
    }
}
