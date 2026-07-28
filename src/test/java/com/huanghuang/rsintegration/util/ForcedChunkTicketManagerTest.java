package com.huanghuang.rsintegration.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ForcedChunkTicketManagerTest {

    @Test
    void independentFeaturesShareOnePhysicalTicket() {
        ForcedChunkTicketManager.RefCounter<String> counter =
                new ForcedChunkTicketManager.RefCounter<>();

        assertTrue(counter.retain("same-machine"));
        assertFalse(counter.retain("same-machine"));
        assertFalse(counter.release("same-machine"));
        assertTrue(counter.release("same-machine"));
        assertFalse(counter.release("same-machine"));
    }

    @Test
    void differentOwnersRemainIndependent() {
        ForcedChunkTicketManager.RefCounter<String> counter =
                new ForcedChunkTicketManager.RefCounter<>();

        assertTrue(counter.retain("first-machine"));
        assertTrue(counter.retain("second-machine"));
        assertTrue(counter.release("first-machine"));
        assertTrue(counter.release("second-machine"));
    }

    @Test
    void drainReturnsEachOutstandingTicketOnce() {
        ForcedChunkTicketManager.RefCounter<String> counter =
                new ForcedChunkTicketManager.RefCounter<>();
        counter.retain("first-machine");
        counter.retain("first-machine");
        counter.retain("second-machine");

        assertTrue(counter.drain().containsAll(
                java.util.List.of("first-machine", "second-machine")));
        assertTrue(counter.drain().isEmpty());
    }
}
