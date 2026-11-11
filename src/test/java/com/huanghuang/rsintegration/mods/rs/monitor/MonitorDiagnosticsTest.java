package com.huanghuang.rsintegration.mods.rs.monitor;

import com.huanghuang.rsintegration.crafting.progress.CraftProgressSnapshot;
import com.huanghuang.rsintegration.crafting.progress.CraftProgressSnapshot.NodeProgress;
import com.huanghuang.rsintegration.crafting.progress.CraftProgressSnapshot.NodeState;
import com.huanghuang.rsintegration.crafting.progress.CraftProgressSnapshot.Reason;
import com.huanghuang.rsintegration.crafting.progress.CraftProgressSnapshot.Result;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class MonitorDiagnosticsTest extends BootstrapTest {
    @Test void evidenceOfAProblemTakesPrecedenceOverLargerQueuesAndFocusUsesNodeIdentity() {
        var diagnostics = MonitorDiagnostics.from(snapshot(Result.RUNNING, List.of(
                node(1, NodeState.READY, Reason.MACHINE_BUSY),
                node(2, NodeState.READY, Reason.MACHINE_BUSY),
                node(3, NodeState.BLOCKED, Reason.NONE),
                node(4, NodeState.READY, Reason.NO_BOUND_MACHINE)), 4));
        assertEquals(MonitorDiagnostics.Kind.ATTENTION, diagnostics.kind());
        assertEquals(Reason.NO_BOUND_MACHINE, diagnostics.reason());
        assertEquals(Set.of("node:4"), diagnostics.focusKeys());
        assertEquals(1, diagnostics.count());
        assertEquals(List.of(MonitorDiagnostics.Kind.ATTENTION, MonitorDiagnostics.Kind.QUEUED, MonitorDiagnostics.Kind.DEPENDENCIES),
                diagnostics.groups().stream().map(MonitorDiagnostics.Group::kind).toList());
        assertEquals("rsi.monitor.advice.binding", key(diagnostics.advice()));
    }

    @Test void normalDependenciesAndSharedMachineQueuesAreNotClassifiedAsFailures() {
        var busy = MonitorDiagnostics.from(snapshot(Result.WAITING, List.of(
                node(1, NodeState.READY, Reason.MACHINE_BUSY)), 1));
        assertEquals(MonitorDiagnostics.Kind.QUEUED, busy.kind());
        assertEquals("rsi.monitor.advice.queue", key(busy.advice()));
        var dependencies = MonitorDiagnostics.from(snapshot(Result.RUNNING, List.of(
                node(1, NodeState.BLOCKED, Reason.NONE),
                node(2, NodeState.BLOCKED, Reason.WAITING_MATERIALS)), 2));
        assertEquals(MonitorDiagnostics.Kind.DEPENDENCIES, dependencies.kind());
        assertEquals("rsi.monitor.advice.materials", key(dependencies.advice()));
    }

    @Test void processingWithDownstreamDependenciesAndLegacyGlobalBusyReasonStillLooksActive() {
        var snapshot = new CraftProgressSnapshot(UUID.randomUUID(), 1, Result.WAITING,
                Reason.MACHINE_BUSY, 0, 2, 1, null, List.of(
                node(1, NodeState.RUNNING, Reason.NONE), node(2, NodeState.BLOCKED, Reason.NONE)));
        var diagnostics = MonitorDiagnostics.from(snapshot);
        assertEquals(MonitorDiagnostics.Kind.RUNNING, diagnostics.kind());
        assertEquals(Reason.NONE, diagnostics.reason());
        assertEquals(1, diagnostics.groups().size(), "下游依赖仍在诊断详情中保留");
        assertEquals("rsi.monitor.advice.running", key(diagnostics.advice()));
    }

    @Test void partialDetailsAreExplicitAndFinishedNodesDoNotLeaveStaleFaults() {
        var snapshot = snapshot(Result.RUNNING, List.of(node(3, NodeState.READY, Reason.CHUNK_UNLOADED)), 100);
        var diagnostics = MonitorDiagnostics.from(snapshot);
        assertTrue(diagnostics.partial());
        assertTrue(diagnostics.tooltip(snapshot).stream().anyMatch(line -> key(line).equals("rsi.monitor.diagnosis.partial")));
        var done = MonitorDiagnostics.from(snapshot(Result.SUCCEEDED, snapshot.nodes(), 100));
        assertEquals(MonitorDiagnostics.Kind.SUCCEEDED, done.kind());
        assertTrue(done.focusKeys().isEmpty());
        var staleNode = MonitorDiagnostics.from(snapshot(Result.RUNNING, List.of(
                node(1, NodeState.SUCCEEDED, Reason.OUTPUT_MISSING)), 1));
        assertTrue(staleNode.groups().isEmpty());
    }

    @Test void failureWithoutNodeDetailsStillHasAUsefulCauseAndNoFakeFocusTarget() {
        var snapshot = new CraftProgressSnapshot(UUID.randomUUID(), CraftProgressSnapshot.TERMINAL_SEQUENCE,
                Result.FAILED, Reason.NETWORK_UNAVAILABLE, 0, 10, 0, null);
        var diagnostics = MonitorDiagnostics.from(snapshot);
        assertEquals(MonitorDiagnostics.Kind.ATTENTION, diagnostics.kind());
        assertEquals("rsi.monitor.advice.network", key(diagnostics.advice()));
        assertTrue(diagnostics.focusKeys().isEmpty());
        assertEquals("rsi.monitor.timing_unknown", key(MonitorDiagnostics.timingLabel(snapshot)));
    }

    @Test void durationFormattingHandlesHoursWithoutWrappingMinutes() {
        assertEquals("00:00", MonitorDiagnostics.duration(0));
        assertEquals("02:03", MonitorDiagnostics.duration(123_999));
        assertEquals("1:01:01", MonitorDiagnostics.duration(3_661_000));
        assertEquals("123:00:00", MonitorDiagnostics.duration(123L * 3_600_000L));
    }

    @Test void queueAndDependencyColorsDifferFromConfirmedProblems() {
        assertEquals(MonitorGridStyle.SCHEDULED, MonitorGridStyle.node(node(1, NodeState.READY, Reason.MACHINE_BUSY)));
        assertEquals(MonitorGridStyle.SCHEDULED, MonitorGridStyle.node(node(2, NodeState.BLOCKED, Reason.WAITING_MATERIALS)));
        assertEquals(MonitorGridStyle.BLOCKED, MonitorGridStyle.node(node(3, NodeState.READY, Reason.NO_BOUND_MACHINE)));
        assertEquals(MonitorGridStyle.BLOCKED, MonitorGridStyle.node(node(4, NodeState.RUNNING, Reason.OUTPUT_MISSING)));
    }

    private static String key(Component component) {
        return component.getContents() instanceof TranslatableContents text ? text.getKey() : "";
    }

    private static CraftProgressSnapshot snapshot(Result result, List<NodeProgress> nodes, int total) {
        return new CraftProgressSnapshot(UUID.randomUUID(), 1, result, Reason.NONE, 0, total, 0, null, nodes);
    }

    private static NodeProgress node(int id, NodeState state, Reason reason) {
        return new NodeProgress(id, state, "test:recipe", "generic", ItemStack.EMPTY,
                0, 10, 0, "", reason, "", false);
    }
}
