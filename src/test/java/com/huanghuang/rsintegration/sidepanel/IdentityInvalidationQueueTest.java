package com.huanghuang.rsintegration.sidepanel;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IdentityInvalidationQueueTest {
    @Test
    void sidePanelInvalidationCallbackOnlyQueuesWorkForTickEnd() throws IOException {
        String source = Files.readString(Path.of("src", "main", "java", "com", "huanghuang",
                "rsintegration", "sidepanel", "RSSidePanelNetworkHandler.java"));

        assertTrue(source.contains("pendingInvalidatedCaches.offer(cache, network)"));
        assertTrue(source.contains("pendingInvalidatedCaches.drain()"));
        assertFalse(source.contains("server.execute(() -> rebindInvalidatedCache"),
                "server.execute may run inline and mutate RS's listener list during iteration");
    }

    @Test
    void coalescesTheSameCacheAndKeepsTheFirstNetwork() {
        IdentityInvalidationQueue<Object, Object> queue = new IdentityInvalidationQueue<>();
        Object cache = new Object();
        Object firstNetwork = new Object();

        queue.offer(cache, firstNetwork);
        queue.offer(cache, new Object());

        var drained = queue.drain();
        assertEquals(1, drained.size());
        assertSame(cache, drained.get(0).getKey());
        assertSame(firstNetwork, drained.get(0).getValue());
        assertTrue(queue.drain().isEmpty());
    }

    @Test
    void usesIdentityInsteadOfEqualsForNativeCaches() {
        IdentityInvalidationQueue<EqualKey, String> queue = new IdentityInvalidationQueue<>();
        EqualKey first = new EqualKey();
        EqualKey second = new EqualKey();

        queue.offer(first, "first");
        queue.offer(second, "second");

        var drained = queue.drain();
        assertEquals(2, drained.size());
        assertTrue(drained.stream().anyMatch(entry -> entry.getKey() == first
                && entry.getValue().equals("first")));
        assertTrue(drained.stream().anyMatch(entry -> entry.getKey() == second
                && entry.getValue().equals("second")));
    }

    private static final class EqualKey {
        @Override public boolean equals(Object ignored) { return true; }
        @Override public int hashCode() { return 1; }
    }
}
