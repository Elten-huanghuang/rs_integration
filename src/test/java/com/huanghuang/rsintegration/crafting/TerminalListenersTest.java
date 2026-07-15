package com.huanghuang.rsintegration.crafting;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TerminalListenersTest {

    @Test
    void runsAllListenersInRegistrationOrder() {
        List<Integer> calls = new ArrayList<>();
        TerminalListeners listeners = new TerminalListeners(error -> {});

        listeners.add(() -> calls.add(1));
        listeners.add(() -> calls.add(2));
        listeners.fireOnce();

        assertEquals(List.of(1, 2), calls);
    }

    @Test
    void failingListenerDoesNotSuppressLaterListeners() {
        AtomicInteger failures = new AtomicInteger();
        AtomicInteger calls = new AtomicInteger();
        TerminalListeners listeners = new TerminalListeners(error -> failures.incrementAndGet());

        listeners.add(() -> { throw new IllegalStateException("boom"); });
        listeners.add(calls::incrementAndGet);
        listeners.fireOnce();

        assertEquals(1, failures.get());
        assertEquals(1, calls.get());
    }

    @Test
    void firesOnlyOnce() {
        AtomicInteger calls = new AtomicInteger();
        TerminalListeners listeners = new TerminalListeners(error -> {});
        listeners.add(calls::incrementAndGet);

        listeners.fireOnce();
        listeners.fireOnce();

        assertEquals(1, calls.get());
    }

    @Test
    void listenerAddedAfterTerminalRunsImmediately() {
        AtomicInteger calls = new AtomicInteger();
        TerminalListeners listeners = new TerminalListeners(error -> {});
        listeners.fireOnce();

        listeners.add(calls::incrementAndGet);

        assertEquals(1, calls.get());
    }
}
