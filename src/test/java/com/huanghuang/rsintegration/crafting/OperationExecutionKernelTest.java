package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.crafting.graph.CaptureLeaseRegistry;
import com.huanghuang.rsintegration.crafting.graph.MachineLeaseRegistry;
import com.huanghuang.rsintegration.crafting.graph.NodeId;
import com.huanghuang.rsintegration.crafting.graph.OperationBudget;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OperationExecutionKernelTest extends BootstrapTest {

    @Test
    void logicalSessionUsesTheSameCommitStartSettleStateMachine() {
        Harness harness = new Harness(1);
        OperationExecutionKernel.Session session = harness.kernel.prepareLogical();
        AtomicInteger settlements = new AtomicInteger();

        assertTrue(session.commit(() -> true));
        assertTrue(session.tryStart(() -> true));
        assertEquals(OperationExecutionKernel.TerminalClass.IN_FLIGHT, session.terminalClass());
        assertEquals(OperationExecutionKernel.CompletionResult.SUCCEEDED,
                session.complete(() -> true, settlements::incrementAndGet));
        session.close();

        assertEquals(1, settlements.get());
        assertEquals(OperationExecutionKernel.TerminalClass.SETTLED, session.terminalClass());
        assertEquals(0, harness.craft.active());
        assertEquals(0, harness.global.active());
    }

    @Test
    void preparedButUnstartedSessionRestoresAllCapacity() {
        Harness harness = new Harness(1);
        OperationExecutionKernel.Session session = harness.prepare(0);

        assertNotNull(session);
        session.close();

        assertEquals(0, harness.craft.active());
        assertEquals(0, harness.craft.starts());
        assertEquals(0, harness.global.active());
        assertEquals(0, harness.global.starts());
        assertEquals(0, harness.machines.size());
        assertNotNull(harness.prepare(1));
    }

    @Test
    void startBoundaryRunsOnceAndConsumesLifetimeBudget() {
        Harness harness = new Harness(1);
        OperationExecutionKernel.Session session = harness.prepare(0);
        AtomicInteger starts = new AtomicInteger();

        assertEquals(OperationExecutionKernel.TerminalClass.PRE_START, session.terminalClass());
        assertTrue(session.commit(() -> true));
        assertTrue(session.tryStart(() -> {
            starts.incrementAndGet();
            return true;
        }));
        assertEquals(OperationExecutionKernel.TerminalClass.IN_FLIGHT, session.terminalClass());
        assertThrows(IllegalStateException.class,
                () -> session.tryStart(() -> true));
        session.close();

        assertEquals(1, starts.get());
        assertEquals(0, harness.craft.active());
        assertEquals(1, harness.craft.starts());
        assertNull(harness.prepare(1));
    }

    @Test
    void startRequiresSuccessfulCommitAndSettlementRunsOnce() {
        Harness harness = new Harness(2);
        OperationExecutionKernel.Session session = harness.prepare(0);
        AtomicInteger settlements = new AtomicInteger();

        assertThrows(IllegalStateException.class,
                () -> session.tryStart(() -> true));
        assertFalse(session.commit(() -> false));
        assertTrue(session.commit(() -> true));
        assertTrue(session.tryStart(() -> true));
        assertEquals(OperationExecutionKernel.TerminalClass.IN_FLIGHT, session.terminalClass());
        session.settle(settlements::incrementAndGet);
        assertEquals(OperationExecutionKernel.TerminalClass.SETTLED, session.terminalClass());
        assertThrows(IllegalStateException.class,
                () -> session.settle(settlements::incrementAndGet));
        session.close();

        assertEquals(1, settlements.get());
        assertTrue(session.committed());
        assertTrue(session.settled());
    }

    @Test
    void outputShortageStillSettlesConsumedInputs() {
        Harness harness = new Harness(2);
        OperationExecutionKernel.Session session = harness.prepare(0);
        AtomicInteger settlements = new AtomicInteger();

        assertTrue(session.commit(() -> true));
        assertTrue(session.tryStart(() -> true));
        assertEquals(OperationExecutionKernel.CompletionResult.OUTPUT_SHORTAGE,
                session.complete(() -> false, settlements::incrementAndGet));

        assertEquals(1, settlements.get());
        assertEquals(OperationExecutionKernel.TerminalClass.SETTLED, session.terminalClass());
        assertThrows(IllegalStateException.class,
                () -> session.complete(() -> true, settlements::incrementAndGet));
        session.close();
    }

    @Test
    void rejectedStartStillCountsAsAttemptAndReleasesActiveResources() {
        Harness harness = new Harness(2);
        OperationExecutionKernel.Session session = harness.prepare(0);

        assertTrue(session.commit(() -> true));
        assertFalse(session.tryStart(() -> false));
        session.close();

        assertEquals(0, harness.craft.active());
        assertEquals(1, harness.craft.starts());
        assertEquals(0, harness.machines.size());
        assertNotNull(harness.prepare(1));
    }

    private static final class Harness {
        final MachineLeaseRegistry machines = new MachineLeaseRegistry();
        final CaptureLeaseRegistry captures = new CaptureLeaseRegistry();
        final OperationBudget global;
        final OperationBudget craft;
        final OperationExecutionKernel kernel;
        final UUID craftId = UUID.randomUUID();

        Harness(int maxStarts) {
            global = new OperationBudget(1, maxStarts);
            craft = new OperationBudget(1, maxStarts);
            kernel = new OperationExecutionKernel(new OperationResourceCoordinator(
                    machines, captures, global));
        }

        OperationExecutionKernel.Session prepare(int operation) {
            return kernel.tryPrepare(craftId, new NodeId(0), operation, craft,
                    new MachineLeaseRegistry.MachineKey(
                            new ResourceLocation("minecraft", "overworld"),
                            new BlockPos(operation, 64, 0), "test"), null);
        }
    }
}
