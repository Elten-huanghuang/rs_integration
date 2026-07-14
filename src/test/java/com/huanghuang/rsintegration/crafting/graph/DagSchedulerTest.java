package com.huanghuang.rsintegration.crafting.graph;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DagSchedulerTest extends BootstrapTest {

    @Test
    void independentLeavesCanBeClaimedTogetherAndUnlockJoin() {
        CraftPlanGraph graph = forkJoinGraph();
        DagScheduler scheduler = new DagScheduler(graph);

        List<NodeId> leaves = scheduler.claimReady(2);
        assertEquals(List.of(new NodeId(0), new NodeId(1)), leaves);
        assertEquals(0, scheduler.readyCount());

        scheduler.succeed(leaves.get(0));
        assertEquals(0, scheduler.readyCount());
        scheduler.succeed(leaves.get(1));
        assertEquals(1, scheduler.readyCount());
        assertEquals(List.of(new NodeId(2)), scheduler.claimReady(1));
    }

    @Test
    void failureStopsDispatchButLetsRunningSiblingDrain() {
        DagScheduler scheduler = new DagScheduler(forkJoinGraph());
        List<NodeId> running = scheduler.claimReady(2);

        scheduler.fail(running.get(0));

        assertTrue(scheduler.isStopping());
        assertFalse(scheduler.isDrained());
        assertEquals(DagScheduler.NodeState.RUNNING, scheduler.state(running.get(1)));
        assertEquals(DagScheduler.NodeState.CANCELLED, scheduler.state(new NodeId(2)));
        scheduler.succeed(running.get(1));
        assertTrue(scheduler.isDrained());
        assertTrue(scheduler.claimReady(2).isEmpty());
    }

    @Test
    void failedReservationCanReleaseClaimBackToReady() {
        DagScheduler scheduler = new DagScheduler(forkJoinGraph());
        NodeId leaf = scheduler.claimReady(1).get(0);

        scheduler.releaseClaim(leaf);

        assertEquals(DagScheduler.NodeState.READY, scheduler.state(leaf));
        assertEquals(2, scheduler.readyCount());
    }

    @Test
    void stateSnapshotTracksLifecycleAndCannotMutateScheduler() {
        DagScheduler scheduler = new DagScheduler(forkJoinGraph());
        assertEquals(Map.of(
                new NodeId(0), DagScheduler.NodeState.READY,
                new NodeId(1), DagScheduler.NodeState.READY,
                new NodeId(2), DagScheduler.NodeState.BLOCKED), scheduler.stateSnapshot());

        NodeId left = scheduler.claimReady(1).get(0);
        assertEquals(DagScheduler.NodeState.RUNNING, scheduler.stateSnapshot().get(left));
        assertThrows(UnsupportedOperationException.class,
                () -> scheduler.stateSnapshot().put(left, DagScheduler.NodeState.FAILED));

        scheduler.succeed(left);
        NodeId right = scheduler.claimReady(1).get(0);
        scheduler.fail(right);

        assertEquals(DagScheduler.NodeState.SUCCEEDED, scheduler.state(new NodeId(0)));
        assertEquals(DagScheduler.NodeState.FAILED, scheduler.state(new NodeId(1)));
        assertEquals(DagScheduler.NodeState.CANCELLED, scheduler.state(new NodeId(2)));
    }

    private static CraftPlanGraph forkJoinGraph() {
        NodeId leftId = new NodeId(0);
        NodeId rightId = new NodeId(1);
        NodeId joinId = new NodeId(2);
        MaterialKey iron = MaterialKey.of(new ItemStack(Items.IRON_INGOT));
        MaterialKey gold = MaterialKey.of(new ItemStack(Items.GOLD_INGOT));
        MaterialKey result = MaterialKey.of(new ItemStack(Items.DIAMOND));
        OutputPortId leftOutput = new OutputPortId(leftId, 0);
        OutputPortId rightOutput = new OutputPortId(rightId, 0);
        OutputPortId joinOutput = new OutputPortId(joinId, 0);
        InputPortId joinLeft = new InputPortId(joinId, 0);
        InputPortId joinRight = new InputPortId(joinId, 1);

        CraftNode left = node(leftId, "left", List.of(),
                List.of(new OutputDeclaration(leftOutput, iron, 1, OutputKind.PRIMARY)));
        CraftNode right = node(rightId, "right", List.of(),
                List.of(new OutputDeclaration(rightOutput, gold, 1, OutputKind.PRIMARY)));
        CraftNode join = node(joinId, "join", List.of(
                        new InputDemand(joinLeft, Ingredient.of(Items.IRON_INGOT), 1,
                                DemandRole.CONSUMED, new ItemStack(Items.IRON_INGOT)),
                        new InputDemand(joinRight, Ingredient.of(Items.GOLD_INGOT), 1,
                                DemandRole.CONSUMED, new ItemStack(Items.GOLD_INGOT))),
                List.of(new OutputDeclaration(joinOutput, result, 1, OutputKind.PRIMARY)));

        return new CraftPlanGraph(1, List.of(left, right, join), List.of(
                new MaterialAllocation(new AllocationId(0), joinLeft,
                        new MaterialSource.ProducerOutput(leftOutput), iron, 1),
                new MaterialAllocation(new AllocationId(1), joinRight,
                        new MaterialSource.ProducerOutput(rightOutput), gold, 1)),
                List.of(new RootDemand(Ingredient.of(Items.DIAMOND), 1, 0,
                        new ItemStack(Items.DIAMOND), List.of(new RootAllocation(
                        new MaterialSource.ProducerOutput(joinOutput), result, 1)))),
                List.of(), List.of(leftId, rightId, joinId));
    }

    private static CraftNode node(NodeId id, String name, List<InputDemand> inputs,
                                  List<OutputDeclaration> outputs) {
        return new CraftNode(id, new ResourceLocation("test", name), ModType.GENERIC.id(),
                new ResourceLocation("minecraft", "crafting"), 1, List.of(), List.of(),
                false, null, null, inputs, outputs);
    }
}
