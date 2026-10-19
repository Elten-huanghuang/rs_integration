package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CraftProgressSnapshotTest extends BootstrapTest {
    @Test
    void boundsNodeDetailsWhileKeepingAuthoritativeSummaryAndActiveNodes() {
        int total = CraftProgressSnapshot.MAX_NODE_DETAILS + 904;
        List<CraftProgressSnapshot.NodeProgress> nodes = new ArrayList<>(total);
        for (int nodeId = 0; nodeId < total; nodeId++) {
            boolean active = nodeId >= total - 100;
            nodes.add(node(active
                    ? CraftProgressSnapshot.NodeState.RUNNING
                    : CraftProgressSnapshot.NodeState.SUCCEEDED, nodeId));
        }

        CraftProgressSnapshot snapshot = new CraftProgressSnapshot(
                UUID.randomUUID(), 7, CraftProgressSnapshot.Result.RUNNING,
                CraftProgressSnapshot.Reason.NONE, total - 100, total, 100,
                null, nodes);

        assertEquals(CraftProgressSnapshot.MAX_NODE_DETAILS, snapshot.nodes().size());
        assertEquals(total, snapshot.totalNodes());
        assertTrue(snapshot.nodes().stream().anyMatch(node ->
                node.nodeId() == total - 1
                        && node.state() == CraftProgressSnapshot.NodeState.RUNNING));
    }

    @Test
    void prioritizesRunningAndFailedDetailsBeforeCompletedNodes() {
        List<CraftProgressSnapshot.NodeProgress> nodes = List.of(
                node(CraftProgressSnapshot.NodeState.SUCCEEDED, 1),
                node(CraftProgressSnapshot.NodeState.BLOCKED, 2),
                node(CraftProgressSnapshot.NodeState.FAILED, 3),
                node(CraftProgressSnapshot.NodeState.RUNNING, 4));

        List<CraftProgressSnapshot.NodeProgress> selected =
                CraftProgressSnapshot.selectNodeDetails(nodes, 2);

        assertEquals(List.of(3, 4), selected.stream()
                .map(CraftProgressSnapshot.NodeProgress::nodeId).toList());
    }

    private static CraftProgressSnapshot.NodeProgress node(
            CraftProgressSnapshot.NodeState state, int nodeId) {
        return new CraftProgressSnapshot.NodeProgress(nodeId, state,
                "test:recipe_" + nodeId, "generic", ItemStack.EMPTY,
                0, 1, state == CraftProgressSnapshot.NodeState.RUNNING ? 1 : 0,
                "", CraftProgressSnapshot.Reason.NONE, "", false);
    }
}
