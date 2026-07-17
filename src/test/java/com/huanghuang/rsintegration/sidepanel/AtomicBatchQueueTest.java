package com.huanghuang.rsintegration.sidepanel;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AtomicBatchQueueTest {

    @Test
    void drainRemovesOnlyCurrentBatch() {
        AtomicBatchQueue<String, Integer> queue = new AtomicBatchQueue<>();
        queue.add("player", 1);
        queue.add("player", 2);

        assertEquals(List.of(1, 2), queue.drain("player"));
        assertTrue(queue.drain("player").isEmpty());

        queue.add("player", 3);
        assertEquals(List.of(3), queue.drain("player"));
    }

    @Test
    void queuesAreIndependentAndClearable() {
        AtomicBatchQueue<String, Integer> queue = new AtomicBatchQueue<>();
        queue.add("a", 1);
        queue.add("b", 2);
        queue.clear("a");

        assertTrue(queue.drain("a").isEmpty());
        assertEquals(List.of(2), queue.drain("b"));
    }
}
