package com.huanghuang.rsintegration.crafting.batch;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanResultGateTest {
    @Test
    void acceptsOnlyOneTerminalResult() {
        PlanResultGate gate = new PlanResultGate();

        assertTrue(gate.tryEnterTerminal());
        assertFalse(gate.tryEnterTerminal());
        assertTrue(gate.isTerminal());
    }

    @Test
    void rejectsStaleGenerationBeforeItCanConsumeTheGate() {
        PlanResultGate gate = new PlanResultGate(42L, 100L);

        assertFalse(gate.tryEnterTerminal(41L));
        assertFalse(gate.isTerminal());
        assertTrue(gate.tryEnterTerminal(42L));
        assertFalse(gate.tryEnterTerminal(42L));
    }

    @Test
    void expirationAndCancellationAreTerminalLifecycleOperations() {
        PlanResultGate expired = new PlanResultGate(7L, 100L);
        assertTrue(expired.expired(200L, 100L));

        PlanResultGate cancelled = new PlanResultGate(7L, 100L);
        cancelled.cancel();
        assertFalse(cancelled.tryEnterTerminal(7L));
    }
}
