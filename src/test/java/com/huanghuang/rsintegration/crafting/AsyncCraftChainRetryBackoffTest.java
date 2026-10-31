package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import com.huanghuang.rsintegration.crafting.graph.MachineLeaseRegistry;
import com.huanghuang.rsintegration.crafting.graph.NodeId;
import com.huanghuang.rsintegration.crafting.graph.OperationId;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 节点重试退避：等机器必须短退避，等材料保留长退避。
 */
class AsyncCraftChainRetryBackoffTest extends BootstrapTest {

    private static final String MACHINE_BUSY = "所有已绑定机器都在忙碌、未加载或不可用";

    @Test
    void machineWaitBacksOffInTicksInsteadOfSeconds() {
        // 切石机这类机器一次只切一刀，长退避会让整条链每两秒才推进一步。
        for (int attempts = 1; attempts <= 6; attempts++) {
            int delay = AsyncCraftChain.retryDelayTicks(MACHINE_BUSY, attempts);
            assertTrue(delay > 0);
            assertTrue(delay <= 6, "机器等待的退避不该超过 6 tick，实际 " + delay);
        }
    }

    @Test
    void materialWaitKeepsLongBackoff() {
        assertEquals(2, AsyncCraftChain.retryDelayTicks("missing material", 1));
        assertEquals(40, AsyncCraftChain.retryDelayTicks("missing material", 6));
    }

    @Test
    void onlyMachineReasonsUseShortBackoff() {
        assertTrue(AsyncCraftChain.isMachineWait(MACHINE_BUSY));
        assertTrue(AsyncCraftChain.isMachineWait(
                "operation budget, machine, or capture is temporarily unavailable"));
        assertFalse(AsyncCraftChain.isMachineWait("missing material"));
        assertFalse(AsyncCraftChain.isMachineWait(null));
    }

    @Test
    void normalMachineQueueDoesNotUseTheThirtySecondRetryDeadline() {
        assertFalse(AsyncCraftChain.shouldAbortGraphRetry(MACHINE_BUSY, 601));
        assertFalse(AsyncCraftChain.shouldAbortGraphRetry(MACHINE_BUSY, 20 * 60 * 60));
        assertFalse(AsyncCraftChain.shouldAbortGraphRetry(
                "operation budget, machine, or capture is temporarily unavailable", 10_000));
        assertFalse(AsyncCraftChain.shouldAbortGraphRetry("missing material", 600));
        assertTrue(AsyncCraftChain.shouldAbortGraphRetry("missing material", 601));
    }

    @Test
    void unrelatedMachineReleaseDoesNotWakeQueuedNode() {
        MachineLeaseRegistry registry = new MachineLeaseRegistry();
        ResourceLocation dimension = new ResourceLocation("minecraft", "overworld");
        var wanted = new MachineLeaseRegistry.MachineKey(dimension, BlockPos.ZERO, "malum");
        var unrelated = new MachineLeaseRegistry.MachineKey(
                dimension, new BlockPos(16, 0, 0), "malum");
        OperationId owner = new OperationId(UUID.randomUUID(), new NodeId(0), 0);
        var wantedLease = registry.tryAcquire(wanted, owner);
        var unrelatedLease = registry.tryAcquire(unrelated, owner);
        List<MachineLeaseRegistry.MachineKey> waiting = List.of(wanted);

        registry.release(unrelatedLease);
        assertFalse(AsyncCraftChain.machineLeaseReleased(waiting, registry));
        assertTrue(AsyncCraftChain.hasLeasedMachine(waiting, registry));
        registry.release(wantedLease);
        assertTrue(AsyncCraftChain.machineLeaseReleased(waiting, registry));
        assertFalse(AsyncCraftChain.hasLeasedMachine(waiting, registry));
        assertFalse(AsyncCraftChain.machineLeaseReleased(List.of(), registry));
    }
}
