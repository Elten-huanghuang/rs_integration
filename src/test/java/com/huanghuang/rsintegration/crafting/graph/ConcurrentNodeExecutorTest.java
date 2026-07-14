package com.huanghuang.rsintegration.crafting.graph;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConcurrentNodeExecutorTest extends BootstrapTest {

    @Test
    void observesAllLeavesBeforeUnlockingJoin() {
        DagScheduler scheduler = new DagScheduler(forkJoinGraph());
        Map<NodeId, FakeWorker> workers = new HashMap<>();
        workers.put(new NodeId(0), new FakeWorker(ConcurrentNodeExecutor.Observation.SUCCEEDED));
        workers.put(new NodeId(1), new FakeWorker(ConcurrentNodeExecutor.Observation.WORKING,
                ConcurrentNodeExecutor.Observation.SUCCEEDED));
        workers.put(new NodeId(2), new FakeWorker(ConcurrentNodeExecutor.Observation.SUCCEEDED));
        ConcurrentNodeExecutor executor = new ConcurrentNodeExecutor(scheduler, workers::get, 2);

        executor.tick();
        assertEquals(2, executor.runningCount());
        executor.tick();
        assertEquals(DagScheduler.NodeState.SUCCEEDED, scheduler.state(new NodeId(0)));
        assertEquals(DagScheduler.NodeState.RUNNING, scheduler.state(new NodeId(1)));
        assertEquals(DagScheduler.NodeState.BLOCKED, scheduler.state(new NodeId(2)));
        executor.tick();
        assertEquals(DagScheduler.NodeState.RUNNING, scheduler.state(new NodeId(2)));
        executor.tick();

        assertTrue(executor.isTerminal());
    }

    @Test
    void failureStopsDispatchAndLetsSiblingReachTerminalObservation() {
        DagScheduler scheduler = new DagScheduler(forkJoinGraph());
        FakeWorker failing = new FakeWorker(ConcurrentNodeExecutor.Observation.FAILED);
        FakeWorker sibling = new FakeWorker(ConcurrentNodeExecutor.Observation.WORKING,
                ConcurrentNodeExecutor.Observation.SUCCEEDED);
        Map<NodeId, FakeWorker> workers = Map.of(new NodeId(0), failing, new NodeId(1), sibling);
        ConcurrentNodeExecutor executor = new ConcurrentNodeExecutor(scheduler, workers::get, 2);

        executor.tick();
        executor.tick();

        assertTrue(scheduler.isStopping());
        assertEquals(1, sibling.stopCalls.get());
        assertEquals(DagScheduler.NodeState.CANCELLED, scheduler.state(new NodeId(2)));
        executor.tick();
        assertTrue(executor.isTerminal());
        assertEquals(1, failing.cleanupCalls.get());
    }

    private static final class FakeWorker implements ConcurrentNodeExecutor.Worker {
        final Queue<ConcurrentNodeExecutor.Observation> observations = new ArrayDeque<>();
        final AtomicInteger stopCalls = new AtomicInteger();
        final AtomicInteger cleanupCalls = new AtomicInteger();

        FakeWorker(ConcurrentNodeExecutor.Observation... observations) {
            this.observations.addAll(List.of(observations));
        }

        @Override
        public ConcurrentNodeExecutor.Observation observe() {
            ConcurrentNodeExecutor.Observation observation = observations.poll();
            return observation == null ? ConcurrentNodeExecutor.Observation.WORKING : observation;
        }

        @Override
        public void stopDispatch() {
            stopCalls.incrementAndGet();
        }

        @Override
        public void cleanupFailure() {
            cleanupCalls.incrementAndGet();
        }
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
                new ResourceLocation("minecraft", "crafting"), 1, List.of(), List.of(), false,
                null, null, inputs, outputs);
    }
}
