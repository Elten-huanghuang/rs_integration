package com.huanghuang.rsintegration.magic;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GoetySoulSourceAccountingTest {
    @Test
    void combinesNetworkAndNativeSourcesForOneRitualBudget() {
        assertEquals(2_500, GoetySoulSourceCoordinator.combine(1_000, 1_500));
        assertEquals(2_500, GoetySoulSourceCoordinator.combine(2_500, 0));
        assertEquals(2_500, GoetySoulSourceCoordinator.combine(0, 2_500));
    }

    @Test
    void saturatesMixedSourceTotalsWithoutOverflow() {
        assertEquals(Integer.MAX_VALUE, GoetySoulSourceCoordinator.combine(
                Integer.MAX_VALUE, Integer.MAX_VALUE));
    }

    @Test
    void consumesNetworkBeforeNativeSource() {
        FakeNetworkPool network = new FakeNetworkPool(1_000);
        FakeNativeSource nativeSource = new FakeNativeSource(2_000);
        GoetySoulSourceCoordinator coordinator =
                GoetySoulSourceCoordinator.create(2_500, network, nativeSource);

        assertTrue(coordinator != null);
        assertTrue(coordinator.consume(1_500));
        assertEquals(0, network.remaining);
        assertEquals(1_500, nativeSource.remaining);
    }

    @Test
    void rejectsTotalBudgetThatNativeAndNetworkCannotCover() {
        FakeNetworkPool network = new FakeNetworkPool(10_000);
        FakeNativeSource nativeSource = new FakeNativeSource(15_000);

        assertNull(GoetySoulSourceCoordinator.create(
                25_001, network, nativeSource));
        assertEquals(10_000, network.remaining);
        assertEquals(15_000, nativeSource.remaining);
    }

    @Test
    void nativeFailureRefundsWithdrawnNetworkReservation() {
        FakeNetworkPool network = new FakeNetworkPool(1_000);
        FakeNativeSource nativeSource = new FakeNativeSource(2_000);
        nativeSource.failConsume = true;
        GoetySoulSourceCoordinator coordinator =
                GoetySoulSourceCoordinator.create(2_500, network, nativeSource);

        assertTrue(coordinator != null);
        assertFalse(coordinator.consume(1_500));
        assertEquals(1_000, network.remaining);
        assertEquals(2_000, nativeSource.remaining);
    }

    @Test
    void virtualContainerUsesCoordinatorWithoutLosingPhysicalBypass() {
        Object owner = new Object();
        FakeContainer container = new FakeContainer(200);
        FakeNetworkPool network = new FakeNetworkPool(50);
        FakeNativeSource nativeSource = new FakeNativeSource(200);
        GoetySoulSourceCoordinator coordinator =
                GoetySoulSourceCoordinator.create(250, network, nativeSource);

        assertTrue(coordinator != null);
        GoetyVirtualSoulAccess.attach(owner, coordinator, List.of(container));
        assertEquals(250, GoetyVirtualSoulAccess.souls(container));
        assertTrue(GoetyVirtualSoulAccess.consume(container, 100));
        assertEquals(150, GoetyVirtualSoulAccess.souls(container));
        assertEquals(200, container.getSouls());

        GoetyVirtualSoulAccess.detach(owner);
        assertEquals(-1, GoetyVirtualSoulAccess.souls(container));
    }

    @Test
    void coordinatorNeverConsumesBeyondTheRecipeBudget() {
        FakeNetworkPool network = new FakeNetworkPool(100);
        FakeNativeSource nativeSource = new FakeNativeSource(1_000);
        GoetySoulSourceCoordinator coordinator =
                GoetySoulSourceCoordinator.create(250, network, nativeSource);

        assertTrue(coordinator != null);
        assertTrue(coordinator.consume(250));
        assertEquals(0, coordinator.available());
        assertFalse(coordinator.consume(1));
        assertEquals(850, nativeSource.remaining);
    }

    @Test
    void networkBudgetAndConsumptionRemainLongAboveInt() {
        long total = Integer.MAX_VALUE + 25L;
        FakeNetworkPool network = new FakeNetworkPool(total);
        FakeNativeSource nativeSource = new FakeNativeSource(0);
        GoetySoulSourceCoordinator coordinator =
                GoetySoulSourceCoordinator.create(total, network, nativeSource);

        assertTrue(coordinator != null);
        assertEquals(total, coordinator.availableAtStart());
        assertTrue(coordinator.consume(total));
        assertEquals(0, network.remaining);
        assertEquals(0, coordinator.available());
    }

    private static final class FakeContainer {
        private int souls;

        private FakeContainer(int souls) {
            this.souls = souls;
        }

        public int getSouls() {
            return souls;
        }

        public void decreaseSouls(int amount) {
            souls -= amount;
        }
    }

    private static final class FakeNetworkPool implements GoetySoulNetworkPool {
        private long remaining;
        private boolean closed;

        private FakeNetworkPool(long remaining) {
            this.remaining = remaining;
        }

        @Override
        public long remaining() {
            return remaining;
        }

        @Override
        public long withdraw(long requested) {
            long moved = Math.min(remaining, Math.max(0, requested));
            remaining -= moved;
            return moved;
        }

        @Override
        public void refund(long amount) {
            remaining += Math.max(0, amount);
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private static final class FakeNativeSource implements GoetyNativeSoulSource {
        private int remaining;
        private boolean failConsume;

        private FakeNativeSource(int remaining) {
            this.remaining = remaining;
        }

        @Override
        public int available() {
            return remaining;
        }

        @Override
        public int consume(int requested) {
            if (failConsume || requested <= 0 || requested > remaining) return 0;
            remaining -= requested;
            return requested;
        }
    }
}
