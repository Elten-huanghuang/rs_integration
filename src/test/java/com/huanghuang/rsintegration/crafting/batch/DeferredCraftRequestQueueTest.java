package com.huanghuang.rsintegration.crafting.batch;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeferredCraftRequestQueueTest {
    @Test
    void newestPreviewReplacesOlderPreviewForSamePlayer() {
        DeferredCraftRequestQueue<String> queue = new DeferredCraftRequestQueue<>(3);
        UUID player = UUID.randomUUID();

        assertTrue(queue.offer(new DeferredCraftRequestQueue.Entry<>(player, true, 1L, "old")));
        assertTrue(queue.offer(new DeferredCraftRequestQueue.Entry<>(player, false, 0L, "execute")));
        assertTrue(queue.offer(new DeferredCraftRequestQueue.Entry<>(player, true, 2L, "new")));

        assertEquals("execute", queue.poll().payload());
        assertEquals("new", queue.poll().payload());
        assertNull(queue.poll());
    }

    @Test
    void capacityRejectsAdditionalExecutionButAllowsPreviewReplacement() {
        DeferredCraftRequestQueue<String> queue = new DeferredCraftRequestQueue<>(2);
        UUID first = UUID.randomUUID();

        assertTrue(queue.offer(new DeferredCraftRequestQueue.Entry<>(first, true, 1L, "old")));
        assertTrue(queue.offer(new DeferredCraftRequestQueue.Entry<>(UUID.randomUUID(), false, 0L, "run")));
        assertFalse(queue.offer(new DeferredCraftRequestQueue.Entry<>(UUID.randomUUID(), false, 0L, "full")));
        assertTrue(queue.offer(new DeferredCraftRequestQueue.Entry<>(first, true, 2L, "new")));
        assertEquals(2, queue.size());
    }

    @Test
    void logoutRemovesOnlyThatPlayersRequests() {
        DeferredCraftRequestQueue<String> queue = new DeferredCraftRequestQueue<>(3);
        UUID leaving = UUID.randomUUID();
        UUID staying = UUID.randomUUID();
        queue.offer(new DeferredCraftRequestQueue.Entry<>(leaving, false, 0L, "leave"));
        queue.offer(new DeferredCraftRequestQueue.Entry<>(staying, false, 0L, "stay"));

        queue.removePlayer(leaving);

        assertEquals("stay", queue.poll().payload());
        assertNull(queue.poll());
    }
}
