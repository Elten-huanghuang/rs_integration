package com.huanghuang.rsintegration.crafting.loadbalancer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OperationQueueTest {

    @Test
    void completedWorkerClaimsAgainWithoutWaitingForSibling() {
        OperationQueue queue = new OperationQueue(4);

        assertEquals(0, queue.claim(10));
        assertEquals(1, queue.claim(20));
        assertEquals(0, queue.complete(10));
        assertEquals(2, queue.claim(10));

        assertEquals(2, queue.runningOperations());
        assertEquals(1, queue.completedOperations());
        assertEquals(1, queue.queuedOperations());
        assertFalse(queue.isComplete());
    }

    @Test
    void tracksEveryOperationExactlyOnce() {
        OperationQueue queue = new OperationQueue(3);

        assertEquals(0, queue.claim(1));
        assertEquals(1, queue.claim(2));
        assertEquals(-1, queue.claim(1));
        queue.complete(2);
        assertEquals(2, queue.claim(2));
        queue.complete(1);
        queue.complete(2);

        assertEquals(3, queue.completedOperations());
        assertEquals(0, queue.runningOperations());
        assertEquals(0, queue.queuedOperations());
        assertTrue(queue.isComplete());
        assertEquals(-1, queue.claim(3));
    }

    @Test
    void drainingStopsFurtherDispatch() {
        OperationQueue queue = new OperationQueue(2);
        queue.claim(1);
        queue.stopDispatch();

        assertTrue(queue.isDispatchStopped());
        assertEquals(-1, queue.claim(2));
        assertFalse(queue.isDrained());
        queue.complete(1);
        assertTrue(queue.isDrained());
        assertFalse(queue.isComplete());
    }

    @Test
    void rejectsCompletingIdleWorker() {
        OperationQueue queue = new OperationQueue(1);
        assertThrows(IllegalStateException.class, () -> queue.complete(5));
    }
}
