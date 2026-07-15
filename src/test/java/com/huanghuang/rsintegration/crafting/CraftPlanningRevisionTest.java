package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.crafting.graph.ConcurrentNodeExecutor;
import com.huanghuang.rsintegration.crafting.graph.CraftNode;
import com.huanghuang.rsintegration.crafting.graph.CraftPlanGraph;
import com.huanghuang.rsintegration.crafting.graph.DagScheduler;
import com.huanghuang.rsintegration.crafting.graph.NodeId;
import com.huanghuang.rsintegration.crafting.graph.RootAllocation;
import com.huanghuang.rsintegration.crafting.graph.RootDemand;
import com.huanghuang.rsintegration.crafting.graph.MaterialKey;
import com.huanghuang.rsintegration.crafting.graph.MaterialSource;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CraftPlanningRevisionTest extends BootstrapTest {

    @Test
    void bumpInvalidatesExistingGraphButNotRunningSettlement() {
        long revision = CraftPlanningRevision.current();
        CraftPlanGraph graph = graph(revision);
        assertTrue(CraftPlanningRevision.isCurrent(graph.planningRevision()));

        DagScheduler scheduler = new DagScheduler(graph);
        AtomicInteger settlements = new AtomicInteger();
        Queue<ConcurrentNodeExecutor.Observation> observations = new ArrayDeque<>(
                List.of(ConcurrentNodeExecutor.Observation.WORKING,
                        ConcurrentNodeExecutor.Observation.SUCCEEDED));
        ConcurrentNodeExecutor executor = new ConcurrentNodeExecutor(scheduler,
                node -> ConcurrentNodeExecutor.StartResult.started(new ConcurrentNodeExecutor.Worker() {
                    @Override public ConcurrentNodeExecutor.Observation observe() {
                        return observations.remove();
                    }
                }), 1, node -> false,
                (node, worker) -> {
                    settlements.incrementAndGet();
                    return ConcurrentNodeExecutor.CompletionStatus.SUCCEEDED;
                });

        executor.tick();
        assertEquals(DagScheduler.NodeState.RUNNING, scheduler.state(new NodeId(0)));
        CraftPlanningRevision.bump();
        assertFalse(CraftPlanningRevision.isCurrent(graph.planningRevision()));

        executor.tick();
        executor.tick();
        assertEquals(1, settlements.get());
        assertTrue(scheduler.allSucceeded());
    }

    @Test
    void newGraphCapturesCurrentRevision() {
        CraftPlanningRevision.bump();
        CraftPlanGraph graph = graph(CraftPlanningRevision.current());
        assertTrue(CraftPlanningRevision.isCurrent(graph.planningRevision()));
    }

    private static CraftPlanGraph graph(long revision) {
        NodeId nodeId = new NodeId(0);
        CraftNode node = new CraftNode(nodeId, new ResourceLocation("test", "node"),
                "generic", new ResourceLocation("minecraft", "crafting"), 1,
                List.of(), List.of(), false, null, null, List.of(), List.of());
        MaterialKey target = MaterialKey.of(new ItemStack(Items.STICK));
        return new CraftPlanGraph(1, List.of(node), List.of(),
                List.of(new RootDemand(Ingredient.of(Items.STICK), 1, 0,
                        new ItemStack(Items.STICK), List.of(new RootAllocation(
                        new MaterialSource.InitialPool(target), target, 1)))),
                List.of(), List.of(nodeId), revision);
    }
}
