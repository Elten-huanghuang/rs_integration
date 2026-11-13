package com.huanghuang.rsintegration.mods.rs.monitor;

import com.huanghuang.rsintegration.crafting.progress.CraftProgressSnapshot;
import com.huanghuang.rsintegration.crafting.progress.CraftProgressSnapshot.NodeProgress;
import com.huanghuang.rsintegration.crafting.progress.CraftProgressSnapshot.NodeState;
import com.huanghuang.rsintegration.crafting.progress.CraftProgressSnapshot.Reason;
import com.huanghuang.rsintegration.crafting.progress.CraftProgressSnapshot.WaitingInput;
import com.huanghuang.rsintegration.crafting.progress.MachineCycleProgress;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class MonitorDependencyDetailsTest extends BootstrapTest {
    @Test void fluidWaitQuantitiesUseMillibucketsAndExternalWaitsNeedAttention() {
        assertEquals("1.2K mB", MonitorDependencyDetails.waitingAmount(true, 1250));
        assertEquals("×1.2K", MonitorDependencyDetails.waitingAmount(false, 1250));
        var waiting = node(1, NodeState.BLOCKED, Reason.WAITING_MATERIALS, List.of(new WaitingInput(
                new ItemStack(Items.COPPER_INGOT), 1250, -1)));
        assertEquals(MonitorDiagnostics.Kind.ATTENTION, MonitorDiagnostics.from(snapshot(List.of(waiting))).kind());
    }
    @Test void followsActualSourceIdsThroughSharedBranchesAndDoesNotCountOneCauseTwice() {
        var root = node(10, NodeState.BLOCKED, Reason.NONE, List.of(input(7), input(8)));
        var left = node(7, NodeState.BLOCKED, Reason.NONE, List.of(input(3)));
        var right = node(8, NodeState.BLOCKED, Reason.NONE, List.of(input(3)));
        var cause = node(3, NodeState.READY, Reason.NO_BOUND_MACHINE, List.of());
        var details = new MonitorDependencyDetails(snapshot(List.of(root, left, right, cause)));
        assertEquals(List.of(3), details.trace(10).causes().stream().map(NodeProgress::nodeId).toList());
        assertFalse(details.trace(10).incomplete());
        var rows = details.rows(10);
        assertEquals(List.of(3, 7, 8), rows.stream().filter(row -> row.targetNodeId() >= 0)
                .map(MonitorDependencyDetails.Row::targetNodeId).toList());
        assertEquals(Reason.NO_BOUND_MACHINE, details.node(rows.get(2).targetNodeId()).reason());
    }

    @Test void runningProducerIsNormalWaitingWhileExternalInputsAreTraceableCauses() {
        var root = node(10, NodeState.BLOCKED, Reason.NONE, List.of(input(7)));
        var running = node(7, NodeState.RUNNING, Reason.NONE, List.of());
        var details = new MonitorDependencyDetails(snapshot(List.of(root, running)));
        assertTrue(details.trace(10).causes().isEmpty());
        assertFalse(details.trace(10).incomplete());
        var external = node(7, NodeState.BLOCKED, Reason.WAITING_MATERIALS, List.of(input(-1)));
        details = new MonitorDependencyDetails(snapshot(List.of(root, external)));
        assertEquals(List.of(7), details.trace(10).causes().stream().map(NodeProgress::nodeId).toList());
        var reason = (TranslatableContents) details.rows(10).get(2).detail().getContents();
        assertEquals("rsi.monitor.wait.material", reason.getKey());
    }

    @Test void missingOrLimitedDetailsStayUnknownAndNeverCreateClickablePhantomSteps() {
        var root = node(10, NodeState.BLOCKED, Reason.NONE, List.of(input(99)));
        var details = new MonitorDependencyDetails(snapshot(List.of(root)));
        assertTrue(details.trace(10).incomplete());
        assertTrue(details.trace(10).causes().isEmpty());
        assertTrue(details.rows(10).stream().noneMatch(row -> row.targetNodeId() >= 0));
        var many = IntStream.range(0, 20).mapToObj(MonitorDependencyDetailsTest::input).toList();
        var limited = node(11, NodeState.BLOCKED, Reason.NONE, many);
        assertEquals(CraftProgressSnapshot.MAX_WAITING_INPUTS, limited.waitingInputs().size());
        assertTrue(limited.waitingInputsTruncated());
    }

    @Test void statusChangesKeepStepOrderAndCopiedStacksDoNotForceFalseProgressUpdates() {
        var first = MonitorStepEntry.filter(MonitorStepEntry.forRsi(snapshot(List.of(
                node(1, NodeState.READY, Reason.NO_BOUND_MACHINE, List.of()),
                node(2, NodeState.RUNNING, Reason.NONE, List.of())))), "", false);
        var second = MonitorStepEntry.filter(MonitorStepEntry.forRsi(snapshot(List.of(
                node(1, NodeState.RUNNING, Reason.NONE, List.of()),
                node(2, NodeState.READY, Reason.NO_BOUND_MACHINE, List.of())))), "", false);
        assertEquals(List.of("node:1", "node:2"), MonitorStepEntry.stabilize(first, second).stream().map(MonitorStepEntry::key).toList());
        assertEquals(node(1, NodeState.BLOCKED, Reason.NONE, List.of(input(7))),
                node(1, NodeState.BLOCKED, Reason.NONE, List.of(input(7))));
    }

    static WaitingInput input(int producer) { return new WaitingInput(new ItemStack(Items.COPPER_INGOT), 8, producer); }
    static NodeProgress node(int id, NodeState state, Reason reason, List<WaitingInput> inputs) {
        return new NodeProgress(id, state, "test:step_" + id, "vanilla_furnace", new ItemStack(Items.IRON_INGOT),
                0, 10, state == NodeState.RUNNING ? 1 : 0, "overworld@1,2,3", reason, "", false,
                inputs, false, MachineCycleProgress.UNKNOWN);
    }
    static CraftProgressSnapshot snapshot(List<NodeProgress> nodes) {
        return new CraftProgressSnapshot(UUID.randomUUID(), 1, CraftProgressSnapshot.Result.RUNNING,
                Reason.NONE, 0, nodes.size(), 1, null, nodes);
    }
}
