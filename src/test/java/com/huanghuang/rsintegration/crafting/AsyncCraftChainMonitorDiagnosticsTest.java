package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.crafting.batch.CraftProgressPacket;
import com.huanghuang.rsintegration.crafting.batch.IBatchDelegate;
import com.huanghuang.rsintegration.crafting.graph.DagScheduler;
import com.huanghuang.rsintegration.crafting.graph.NodeId;
import com.huanghuang.rsintegration.crafting.progress.CraftProgressSnapshot;
import com.huanghuang.rsintegration.crafting.progress.CraftProgressSnapshot.Reason;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.huanghuang.rsintegration.crafting.GraphPreparationFixture.component;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AsyncCraftChainMonitorDiagnosticsTest {
    @Test void noBindingsAndUnloadedChunksHaveDistinctEvidenceWithoutChangingRetryDecisions() throws Throwable {
        try (var fixture = new GraphPreparationFixture(0, 1, true)) {
            var result = fixture.prepare();
            assertEquals("FATAL", component(result, "state").toString());
            assertEquals(Reason.NO_BOUND_MACHINE, component(result, "reason"));
        }
        try (var fixture = new GraphPreparationFixture(2, 1, true)) {
            fixture.unavailable.addAll(List.of(0, 1));
            var result = fixture.prepare();
            assertEquals("RETRY", component(result, "state").toString());
            assertEquals(Reason.CHUNK_UNLOADED, component(result, "reason"));
            var detail = (String) component(result, "detail");
            assertTrue(AsyncCraftChain.isMachineWait(detail), "监控分类不能改变现有短退避规则");
            invokeRetry(fixture.chain, detail, Reason.CHUNK_UNLOADED);
            var node = nodeSnapshot(fixture.chain);
            assertEquals(Reason.CHUNK_UNLOADED, node.reason());
            assertEquals(detail, node.technicalDetail());
            assertTrue(node.machineLabel().contains("@0, 64, 0"));
            assertTrue(node.machineLabel().contains("(+1)"));
            var snapshot = new CraftProgressSnapshot(UUID.randomUUID(), 1, CraftProgressSnapshot.Result.WAITING,
                    Reason.NONE, 0, 1, 0, null, List.of(node));
            var buffer = new FriendlyByteBuf(Unpooled.buffer());
            try {
                new CraftProgressPacket(snapshot).encode(buffer);
                var decoded = CraftProgressPacket.decode(buffer).snapshot().nodes().get(0);
                assertEquals(node.reason(), decoded.reason());
                assertEquals(node.machineLabel(), decoded.machineLabel());
                assertEquals(node.technicalDetail(), decoded.technicalDetail());
            } finally { buffer.release(); }
        }
    }

    @Test void adapterRejectionsStayUncertainAndRetryEvidenceChangesWhenTheCauseChanges() throws Throwable {
        try (var fixture = new GraphPreparationFixture(1, 1, true)) {
            fixture.resultAt = index -> IBatchDelegate.PreparationResult.retry("needs energy");
            var result = fixture.prepare();
            assertEquals(Reason.UNKNOWN, component(result, "reason"), "不能从自然语言猜测机器错误类型");
            invokeRetry(fixture.chain, "needs energy", Reason.UNKNOWN);
            assertEquals(Reason.UNKNOWN, nodeSnapshot(fixture.chain).reason());
            invokeRetry(fixture.chain, "material admission unavailable", Reason.WAITING_MATERIALS);
            var waiting = nodeSnapshot(fixture.chain);
            assertEquals(Reason.WAITING_MATERIALS, waiting.reason());
            assertEquals("material admission unavailable", waiting.technicalDetail());
            assertTrue(waiting.machineLabel().contains("@0, 64, 0"));
        }
    }

    @Test void laterFailureReplacesAnEarlierRetryReasonWithoutLosingTheKnownMachineLocation() throws Throwable {
        try (var fixture = new GraphPreparationFixture(1, 1, true)) {
            fixture.prepare();
            invokeRetry(fixture.chain, "machine occupied", Reason.MACHINE_BUSY);
            map(fixture.chain, "graphFailureCodes").put(GraphPreparationFixture.NODE, CraftFailureCode.CONTRACT_INCOMPATIBLE);
            map(fixture.chain, "graphFailureDetails").put(GraphPreparationFixture.NODE, "plan became stale after reload");
            var node = nodeSnapshot(fixture.chain, DagScheduler.NodeState.FAILED);
            assertEquals(Reason.CONTRACT_INCOMPATIBLE, node.reason());
            assertEquals("plan became stale after reload", node.technicalDetail());
            assertTrue(node.machineLabel().contains("@0, 64, 0"));
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<NodeId, Object> map(AsyncCraftChain chain, String name) throws Exception {
        var field = AsyncCraftChain.class.getDeclaredField(name);
        field.setAccessible(true);
        return (Map<NodeId, Object>) field.get(chain);
    }

    private static void invokeRetry(AsyncCraftChain chain, String detail, Reason reason) throws Exception {
        var method = AsyncCraftChain.class.getDeclaredMethod("graphRetry", NodeId.class, String.class, List.class, Reason.class);
        method.setAccessible(true);
        method.invoke(chain, GraphPreparationFixture.NODE, detail, List.of(), reason);
    }

    private static CraftProgressSnapshot.NodeProgress nodeSnapshot(AsyncCraftChain chain) throws Exception {
        return nodeSnapshot(chain, DagScheduler.NodeState.READY);
    }

    private static CraftProgressSnapshot.NodeProgress nodeSnapshot(AsyncCraftChain chain, DagScheduler.NodeState state) throws Exception {
        var scheduler = mock(DagScheduler.class);
        when(scheduler.state(GraphPreparationFixture.NODE)).thenReturn(state);
        var field = AsyncCraftChain.class.getDeclaredField("graphScheduler");
        field.setAccessible(true);
        field.set(chain, scheduler);
        var method = AsyncCraftChain.class.getDeclaredMethod("buildGraphNodeProgress", NodeId.class);
        method.setAccessible(true);
        return (CraftProgressSnapshot.NodeProgress) method.invoke(chain, GraphPreparationFixture.NODE);
    }
}
