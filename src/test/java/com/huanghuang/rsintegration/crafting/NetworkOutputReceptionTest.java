package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.api.IntegrationResourceKey;
import com.huanghuang.rsintegration.api.IntegrationResourceKey.ResourceKind;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.fluids.FluidStack;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class NetworkOutputReceptionTest extends BootstrapTest {
    private final IntegrationResourceKey iron = IntegrationResourceKey.item(new ItemStack(Items.IRON_INGOT));

    private TaskOutputReception.Session waiting(Object network, long amount) {
        var session = TaskOutputReception.open();
        session.bindNetwork(network, () -> true);
        session.prepare(List.of(new TaskOutputReception.Demand("out", iron, amount, amount))).activate();
        return session;
    }

    @Test
    void directAndNetworkTransfersShareProgressAndHoldingWithExactSurplus() {
        Object network = new Object();
        try (var session = waiting(network, 8)) {
            session.current().collect(iron, 3);
            assertEquals(5, TaskOutputReception.receive(network, iron, 7, false));
            assertTrue(session.current().complete());
            assertEquals(8, session.received(iron));
            assertEquals(8, session.drainStacks().get(0).getCount());
            assertTrue(session.drain().isEmpty());
            assertEquals(0, TaskOutputReception.receive(network, iron, 7, false));
            // 又一次真实抽取仍是新的实物，不能因为收货进度已满而丢弃。
            assertEquals(0, session.current().collect(iron, 2));
            assertEquals(2, session.drain().get(0).amount());
        }
    }

    @Test
    void exactResourceAndNetworkIdentityAreRequired() {
        Object network = new String("same");
        Object equalButDifferent = new String("same");
        try (var session = waiting(network, 4)) {
            ItemStack tagged = new ItemStack(Items.IRON_INGOT);
            tagged.getOrCreateTag().putString("grade", "pure");
            assertEquals(0, TaskOutputReception.receive(equalButDifferent, iron, 4, false));
            assertEquals(0, TaskOutputReception.receive(network, IntegrationResourceKey.item(tagged), 4, false));
            assertEquals(0, TaskOutputReception.receive(network,
                    IntegrationResourceKey.fluid(new FluidStack(Fluids.WATER, 4)), 4, false));
            // 箱子或绕开入口的变化没有调用 receive，不影响持有和进度。
            assertTrue(session.drain().isEmpty());
            assertEquals(4, session.current().remaining("out"));
        }
    }

    @Test
    void simulateNeverActivatesClosesOrHoldsAndOnlyActiveWaitersClaim() {
        Object network = new Object();
        AtomicBoolean valid = new AtomicBoolean(true);
        try (var session = TaskOutputReception.open()) {
            session.bindNetwork(network, valid::get);
            var batch = session.prepare(List.of(new TaskOutputReception.Demand("out", iron, 4, 4)));
            assertEquals(0, TaskOutputReception.receive(network, iron, 4, false));
            batch.activate();
            for (int i = 0; i < 3; i++) assertEquals(4, TaskOutputReception.receive(network, iron, 8, true));
            assertEquals(0, session.received(iron));
            assertEquals(4, batch.remaining("out"));
            assertTrue(session.drain().isEmpty());
            valid.set(false);
            assertEquals(0, TaskOutputReception.receive(network, iron, 8, true));
            valid.set(true);
            assertEquals(4, TaskOutputReception.receive(network, iron, 4, false));
        }
    }

    @Test
    void waitersUseRegistrationOrderAndLifecycleRemovesEligibility() {
        Object network = new Object();
        try (var first = waiting(network, 3); var second = waiting(network, 5)) {
            assertEquals(6, TaskOutputReception.receive(network, iron, 6, false));
            assertEquals(3, first.received(iron));
            assertEquals(3, second.received(iron));
            second.current().close();
            assertEquals(0, TaskOutputReception.receive(network, iron, 8, false));
            second.prepare(List.of(new TaskOutputReception.Demand("new", iron, 2, 2))).activate();
            assertEquals(1, TaskOutputReception.receive(network, iron, 1, false));
            second.close();
            assertEquals(0, TaskOutputReception.receive(network, iron, 1, false));
            assertEquals(4, second.drain().get(0).amount());
        }
    }

    @Test
    void warmedWaitersIncludeNewBatchesAndRemoveCompletedOnesInRegistrationOrder() {
        Object network = new Object();
        try (var first = waiting(network, 2); var second = waiting(network, 3)) {
            assertEquals(4, TaskOutputReception.receive(network, iron, 4, true));
            try (var third = waiting(network, 4)) {
                assertEquals(7, TaskOutputReception.receive(network, iron, 7, false));
                assertEquals(2, first.received(iron));
                assertEquals(3, second.received(iron));
                assertEquals(2, third.received(iron));
                assertEquals(2, TaskOutputReception.receive(network, iron, 5, true));
                assertEquals(2, TaskOutputReception.receive(network, iron, 5, false));
                assertFalse(TaskOutputReception.hasWaitingResources(network));
                assertFalse(TaskOutputReception.hasWaitingResourceType(network, ResourceKind.ITEM, iron.id()));
                assertEquals(4, third.drain().get(0).amount());
            }
        }
    }

    @Test
    void warmedWaitersDoNotRetainClosedReboundOrInvalidSessions() {
        Object network = new Object();
        Object other = new Object();
        try (var first = waiting(network, 2);
             var second = waiting(network, 3);
             var third = waiting(network, 4)) {
            assertEquals(1, TaskOutputReception.receive(network, iron, 1, true));
            first.close();
            second.bindNetwork(other, () -> true);
            assertEquals(4, TaskOutputReception.receive(network, iron, 9, false));
            assertEquals(0, first.received(iron));
            assertEquals(0, second.received(iron));
            assertFalse(TaskOutputReception.hasWaitingResources(network));
            assertEquals(1, TaskOutputReception.receive(other, iron, 1, true));
            second.bindNetwork(other, () -> false);
            assertEquals(0, TaskOutputReception.receive(other, iron, 1, false));
            assertFalse(TaskOutputReception.hasWaitingResourceType(other, ResourceKind.ITEM, iron.id()));
            assertEquals(4, third.drain().get(0).amount());
        }
    }

    @Test
    void coarseTypesKeepOtherNbtVariantsAndCountDuplicatePortsOnlyOnce() {
        Object network = new Object();
        ItemStack tagged = new ItemStack(Items.IRON_INGOT);
        tagged.getOrCreateTag().putString("grade", "pure");
        var pure = IntegrationResourceKey.item(tagged);
        try (var first = TaskOutputReception.open(); var second = TaskOutputReception.open()) {
            first.bindNetwork(network, () -> true);
            var batch = first.prepare(List.of(
                    new TaskOutputReception.Demand("a", iron, 1, 1),
                    new TaskOutputReception.Demand("b", iron, 1, 1)));
            assertFalse(TaskOutputReception.hasWaitingResourceType(network, ResourceKind.ITEM, iron.id()));
            batch.activate();
            second.bindNetwork(network, () -> true);
            second.prepare(List.of(new TaskOutputReception.Demand("pure", pure, 3, 3))).activate();
            assertTrue(TaskOutputReception.hasWaitingResourceType(network, ResourceKind.ITEM, iron.id()));
            assertFalse(TaskOutputReception.hasWaitingResourceType(network, ResourceKind.FLUID, iron.id()));
            assertEquals(2, TaskOutputReception.receive(network, iron, 9, false));
            first.close();
            batch.close();
            assertTrue(TaskOutputReception.hasWaitingResourceType(network, ResourceKind.ITEM, iron.id()));
            assertEquals(0, TaskOutputReception.receive(network, iron, 9, false));
            assertEquals(3, TaskOutputReception.receive(network, pure, 9, false));
            assertFalse(TaskOutputReception.hasWaitingResourceType(network, ResourceKind.ITEM, iron.id()));
        }
    }

    @Test
    void invalidationPruningAndRebindingNeverLoseHeldResources() {
        Object oldNetwork = new Object();
        Object newNetwork = new Object();
        AtomicBoolean valid = new AtomicBoolean(true);
        try (var session = waiting(oldNetwork, 6)) {
            assertEquals(1, TaskOutputReception.receive(oldNetwork, iron, 1, false));
            session.bindNetwork(newNetwork, valid::get);
            assertEquals(0, TaskOutputReception.receive(oldNetwork, iron, 1, false));
            assertEquals(1, TaskOutputReception.receive(newNetwork, iron, 1, false));
            valid.set(false);
            TaskOutputReception.pruneNetwork(newNetwork);
            valid.set(true);
            assertEquals(0, TaskOutputReception.receive(newNetwork, iron, 1, false));
            session.bindNetwork(newNetwork, valid::get);
            TaskOutputReception.invalidateNetwork(newNetwork);
            assertEquals(0, TaskOutputReception.receive(newNetwork, iron, 1, false));
            assertEquals(2, session.drain().get(0).amount());
        }
    }

    @Test
    void rebindingSameNetworkRefreshesValidityWithoutDuplicatingWaiters() {
        Object network = new Object();
        AtomicBoolean valid = new AtomicBoolean(true);
        try (var session = waiting(network, 4)) {
            session.bindNetwork(network, valid::get);
            valid.set(false);
            assertEquals(0, TaskOutputReception.receive(network, iron, 4, true));
            assertEquals(4, session.current().remaining("out"));
            assertTrue(session.drain().isEmpty());
            valid.set(true);
            assertEquals(3, TaskOutputReception.receive(network, iron, 3, false));
            session.bindNetwork(network, () -> false);
            assertEquals(0, TaskOutputReception.receive(network, iron, 4, false));
            assertEquals(3, session.drain().get(0).amount());
            session.bindNetwork(network, () -> true);
            assertEquals(1, TaskOutputReception.receive(network, iron, 4, false));
            assertEquals(4, session.received(iron));
            assertEquals(1, session.drain().get(0).amount());
        }
    }

    @Test
    void nestedSameNetworkOnlyClaimsOnceAndIndependentCallsCanContinue() {
        Object network = new Object();
        try (var session = waiting(network, 10)) {
            long left = NetworkOutputReception.<Long>route(network, iron, 3, false, remainder -> {
                assertEquals(0, remainder);
                // 模拟原存储路径递归；此调用不得再给等待项增加三件。
                return NetworkOutputReception.<Long>route(network, iron, 3, false, inner -> inner);
            });
            assertEquals(3, left);
            assertEquals(3, session.received(iron));
            assertEquals(0L, NetworkOutputReception.<Long>route(network, iron, 3, false, remainder -> remainder));
            assertEquals(6, session.received(iron));
        }
    }

    @Test
    void internalWritebackIsNestedAndExceptionSafeAcrossNetworks() {
        Object a = new Object();
        Object b = new Object();
        try (var first = waiting(a, 3); var second = waiting(b, 3)) {
            NetworkOutputReception.internal(() -> {
                assertEquals(3L, NetworkOutputReception.<Long>route(a, iron, 3, false, left -> left));
                assertThrows(IllegalStateException.class, () -> NetworkOutputReception.internal(() -> {
                    throw new IllegalStateException("test");
                }));
                assertEquals(3L, NetworkOutputReception.<Long>route(b, iron, 3, false, left -> left));
                return null;
            });
            assertEquals(0, first.received(iron));
            assertEquals(0, second.received(iron));
            assertThrows(IllegalStateException.class, () -> NetworkOutputReception.<Long>route(
                    a, iron, 1, false, left -> { throw new IllegalStateException("storage"); }));
            assertEquals(0L, NetworkOutputReception.<Long>route(a, iron, 2, false, left -> left));
            assertEquals(3, first.received(iron));
        }
    }

    @Test
    void reusedRoutingScopeAllowsCrossNetworkNestingAndRecoversAfterExceptions() {
        Object firstNetwork = new Object();
        Object secondNetwork = new Object();
        try (var first = waiting(firstNetwork, 5); var second = waiting(secondNetwork, 5)) {
            assertThrows(IllegalStateException.class, () -> NetworkOutputReception.route(
                    firstNetwork, iron, 1, false, outer -> NetworkOutputReception.route(
                            secondNetwork, iron, 1, false, inner -> {
                                assertEquals(1L, NetworkOutputReception.<Long>route(
                                        firstNetwork, iron, 1, false, left -> left));
                                throw new IllegalStateException("storage");
                            })));
            assertTrue(NetworkOutputReception.canReceive(firstNetwork));
            assertTrue(NetworkOutputReception.canReceive(secondNetwork));
            for (int i = 0; i < 4; i++) {
                assertEquals(0L, NetworkOutputReception.<Long>route(
                        firstNetwork, iron, 1, false, left -> left));
                assertEquals(0L, NetworkOutputReception.<Long>route(
                        secondNetwork, iron, 1, false, left -> left));
            }
            assertEquals(5, first.drain().get(0).amount());
            assertEquals(5, second.drain().get(0).amount());
        }
    }

    @Test
    void backendNeutralAmountsCanExceedRsIntLimit() {
        Object network = new Object();
        long amount = (long) Integer.MAX_VALUE + 100;
        try (var session = waiting(network, amount)) {
            assertEquals(5L, NetworkOutputReception.<Long>route(network, iron, amount + 5, false, left -> left));
            assertEquals(amount, session.drain().get(0).amount());
        }
    }

    @Test
    void randomizedTransfersConserveHoldingStorageAndRemainder() {
        Object network = new Object();
        Random random = new Random(3191);
        try (var session = waiting(network, 1000)) {
            long supplied = 0;
            long stored = 0;
            long unaccepted = 0;
            for (int i = 0; i < 400; i++) {
                int amount = random.nextInt(17) + 1;
                supplied += amount;
                if (random.nextBoolean()) session.current().collect(iron, amount);
                else {
                    long left = NetworkOutputReception.<Long>route(network, iron, amount, false, value -> value);
                    long disk = Math.min(left, random.nextInt(8));
                    stored += disk;
                    unaccepted += left - disk;
                }
            }
            assertEquals(supplied, session.drain().get(0).amount() + stored + unaccepted);
        }
    }
}
