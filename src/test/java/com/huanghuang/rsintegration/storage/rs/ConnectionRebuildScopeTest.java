package com.huanghuang.rsintegration.storage.rs;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConnectionRebuildScopeTest {
    @Test
    void separatesItemFluidNetworksAndConsecutiveRebuilds() {
        Object items = new Object();
        Object fluids = new Object();
        ConnectionRebuildScope.execute(items, fluids, () -> {
            assertFalse(ConnectionRebuildScope.alreadyRebuilt(items));
            ConnectionRebuildScope.record(items, true);
            assertTrue(ConnectionRebuildScope.alreadyRebuilt(items));
            assertFalse(ConnectionRebuildScope.alreadyRebuilt(fluids));
            assertFalse(ConnectionRebuildScope.alreadyRebuilt(new Object()));
            ConnectionRebuildScope.record(fluids, true);
            assertTrue(ConnectionRebuildScope.alreadyRebuilt(fluids));
            ConnectionRebuildScope.record(items, false);
            assertFalse(ConnectionRebuildScope.alreadyRebuilt(items));
        });
        assertFalse(ConnectionRebuildScope.alreadyRebuilt(fluids));
        ConnectionRebuildScope.execute(items, fluids, () -> assertFalse(ConnectionRebuildScope.alreadyRebuilt(fluids)));
    }

    @Test
    void nestedRebuildInvalidatesOuterCompletionAndRestoresContext() {
        Object items = new Object();
        Object fluids = new Object();
        ConnectionRebuildScope.execute(items, fluids, () -> {
            ConnectionRebuildScope.record(items, true);
            assertThrows(IllegalStateException.class, () ->
                    ConnectionRebuildScope.execute(new Object(), new Object(), () -> {
                        assertFalse(ConnectionRebuildScope.alreadyRebuilt(items));
                        throw new IllegalStateException("模拟嵌套重建失败");
                    }));
            assertFalse(ConnectionRebuildScope.alreadyRebuilt(items));
            ConnectionRebuildScope.record(items, true);
            assertTrue(ConnectionRebuildScope.alreadyRebuilt(items));
        });
        assertThrows(IllegalStateException.class, () -> ConnectionRebuildScope.execute(items, fluids, () -> {
            ConnectionRebuildScope.record(items, true);
            throw new IllegalStateException("模拟重建失败");
        }));
        assertFalse(ConnectionRebuildScope.alreadyRebuilt(items));
    }

    @Test
    void sessionsDoNotLeakToAnotherThread() {
        Object items = new Object();
        ConnectionRebuildScope.execute(items, new Object(), () -> {
            ConnectionRebuildScope.record(items, true);
            AtomicBoolean observed = new AtomicBoolean(true);
            Thread worker = new Thread(() -> observed.set(ConnectionRebuildScope.alreadyRebuilt(items)));
            worker.start();
            try {
                worker.join();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
            }
            assertFalse(observed.get());
            assertTrue(ConnectionRebuildScope.alreadyRebuilt(items));
        });
    }
}
