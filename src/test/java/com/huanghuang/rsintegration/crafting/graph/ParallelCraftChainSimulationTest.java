package com.huanghuang.rsintegration.crafting.graph;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.crafting.OperationExecutionKernel;
import com.huanghuang.rsintegration.crafting.OperationResourceCoordinator;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Demonstrates that independent graph branches really execute concurrently. */
class ParallelCraftChainSimulationTest extends BootstrapTest {

    @Test
    void eightIndependentMachinesRunAtTheConfiguredParallelLimit() {
        final int width = 8;
        CraftPlanGraph graph = build(width);
        DagScheduler scheduler = new DagScheduler(graph);
        MaterialBroker broker = new MaterialBroker();
        NodeAdmissionCoordinator admissions = new NodeAdmissionCoordinator(scheduler, broker);
        MachineLeaseRegistry machines = new MachineLeaseRegistry();
        CaptureLeaseRegistry captures = new CaptureLeaseRegistry();
        OperationResourceCoordinator resources = new OperationResourceCoordinator(machines, captures,
                new OperationBudget(width, width));
        OperationExecutionKernel kernel = new OperationExecutionKernel(resources);
        OperationBudget craftBudget = new OperationBudget(width, width);
        UUID craftId = UUID.randomUUID();
        List<NodeAdmissionCoordinator.Admission> admissionsInFlight = new ArrayList<>();
        List<OperationExecutionKernel.Session> sessions = new ArrayList<>();

        for (int index = 0; index < width; index++) {
            MaterialKey input = material(index);
            broker.publish(new MaterialSource.InitialPool(input), input, 1);
        }

        assertEquals(width, scheduler.claimReady(width).size());
        for (int index = 0; index < width; index++) {
            NodeId node = new NodeId(index);
            MaterialKey input = material(index);
            MaterialSource source = new MaterialSource.InitialPool(input);
            NodeAdmissionCoordinator.Admission admission = admissions.tryAdmitClaimed(
                    new NodeAdmissionCoordinator.Candidate(node,
                            List.of(new MaterialBroker.Request(source, input, 1))));
            assertNotNull(admission);
            OperationExecutionKernel.Session session = kernel.tryPrepare(craftId, node, 0,
                    craftBudget, new MachineLeaseRegistry.MachineKey(
                            new ResourceLocation("minecraft", "overworld"),
                            new BlockPos(index, 64, 0), "parallel_machine"), null);
            assertNotNull(session);
            assertTrue(session.commit(() -> { admissions.commit(admission); return true; }));
            assertTrue(session.tryStart(() -> true));
            admissionsInFlight.add(admission);
            sessions.add(session);
        }

        assertEquals(width, craftBudget.active());
        assertEquals(width, machines.size());
        assertEquals(width, scheduler.runningNodes().size());

        for (int index = 0; index < width; index++) {
            NodeId node = new NodeId(index);
            final int current = index;
            assertEquals(OperationExecutionKernel.CompletionResult.SUCCEEDED,
                    sessions.get(current).complete(() -> true,
                            () -> admissions.settleMaterial(admissionsInFlight.get(current))));
            sessions.get(index).close();
            scheduler.succeed(node);
        }

        assertTrue(scheduler.allSucceeded());
        assertEquals(0, craftBudget.active());
        assertEquals(0, machines.size());
        assertEquals(0, captures.size());
    }

    @Test
    void twoHundredFiftySixMachinesRunInSixteenParallelLayers() {
        final int layers = 16;
        final int width = 16;
        final int total = layers * width;
        CraftPlanGraph graph = layeredGraph(layers, width);
        DagScheduler scheduler = new DagScheduler(graph);
        MaterialBroker broker = new MaterialBroker();
        MachineLeaseRegistry machines = new MachineLeaseRegistry();
        OperationBudget budget = new OperationBudget(width, total);
        int peakParallel = 0;

        for (int lane = 0; lane < width; lane++) {
            MaterialKey input = layeredMaterial(0, lane);
            broker.publish(new MaterialSource.InitialPool(input), input, 1);
        }
        for (int layer = 0; layer < layers; layer++) {
            List<NodeId> running = scheduler.claimReady(width);
            assertEquals(width, running.size());
            List<MachineLeaseRegistry.Lease> leases = new ArrayList<>();
            List<MaterialBroker.ReservationToken> tokens = new ArrayList<>();
            List<OperationBudget.Permit> permits = new ArrayList<>();
            for (int lane = 0; lane < width; lane++) {
                NodeId node = running.get(lane);
                MaterialKey input = layeredMaterial(layer, lane);
                MaterialSource source = layer == 0
                        ? new MaterialSource.InitialPool(input)
                        : new MaterialSource.ProducerOutput(new OutputPortId(
                                new NodeId((layer - 1) * width + lane), 0));
                MaterialBroker.ReservationToken token = broker.reserve(node,
                        List.of(new MaterialBroker.Request(source, input, 1)));
                assertNotNull(token);
                broker.commit(token);
                tokens.add(token);
                MachineLeaseRegistry.Lease lease = machines.tryAcquire(
                        new MachineLeaseRegistry.MachineKey(
                                new ResourceLocation("minecraft", "overworld"),
                                new BlockPos(lane, 64, layer), "layered_machine"),
                        new MachineLeaseRegistry.Owner(UUID.randomUUID(), node, 0));
                assertNotNull(lease);
                leases.add(lease);
                OperationBudget.Permit permit = budget.tryAcquire();
                assertNotNull(permit);
                permits.add(permit);
            }
            peakParallel = Math.max(peakParallel, machines.size());
            assertEquals(width, budget.active());

            for (int lane = 0; lane < width; lane++) {
                NodeId node = running.get(lane);
                broker.settle(tokens.get(lane));
                MaterialKey output = layeredMaterial(layer + 1, lane);
                MaterialSource outputSource = new MaterialSource.ProducerOutput(
                        new OutputPortId(node, 0));
                broker.publish(outputSource, output, 1);
                assertTrue(machines.release(leases.get(lane)));
                permits.get(lane).close();
                scheduler.succeed(node, ignored -> true);
            }
        }

        assertEquals(width, peakParallel);
        assertEquals(total, scheduler.countSucceeded());
        assertTrue(scheduler.allSucceeded());
        assertEquals(0, machines.size());
        assertEquals(0, budget.active());
        assertEquals(width, broker.drainAvailableProducerAssets().stream()
                .mapToInt(ItemStack::getCount).sum());
    }

    private static CraftPlanGraph build(int width) {
        List<CraftNode> nodes = new ArrayList<>();
        List<MaterialAllocation> allocations = new ArrayList<>();
        List<RootDemand> roots = new ArrayList<>();
        for (int index = 0; index < width; index++) {
            NodeId node = new NodeId(index);
            MaterialKey input = material(index);
            MaterialKey output = material(width + index);
            InputPortId inputId = new InputPortId(node, 0);
            OutputPortId outputId = new OutputPortId(node, 0);
            nodes.add(new CraftNode(node, new ResourceLocation("test", "parallel_" + index),
                    ModType.GENERIC.id(), new ResourceLocation("test", "parallel"), 1,
                    List.of(), List.of(), false, null, null,
                    List.of(new InputDemand(inputId, Ingredient.of(input.toStack(1)), 1,
                            DemandRole.CONSUMED, input.toStack(1))),
                    List.of(new OutputDeclaration(outputId, output, 1, OutputKind.PRIMARY))));
            allocations.add(new MaterialAllocation(new AllocationId(index), inputId,
                    new MaterialSource.InitialPool(input), input, 1));
            roots.add(new RootDemand(Ingredient.of(output.toStack(1)), 1, 0, output.toStack(1),
                    List.of(new RootAllocation(new MaterialSource.ProducerOutput(outputId), output, 1))));
        }
        return new CraftPlanGraph(1, nodes, allocations, roots, List.of(),
                nodes.stream().map(CraftNode::id).toList());
    }

    private static CraftPlanGraph layeredGraph(int layers, int width) {
        List<CraftNode> nodes = new ArrayList<>();
        List<MaterialAllocation> allocations = new ArrayList<>();
        List<RootDemand> roots = new ArrayList<>();
        for (int layer = 0; layer < layers; layer++) {
            for (int lane = 0; lane < width; lane++) {
                int value = layer * width + lane;
                NodeId node = new NodeId(value);
                InputPortId inputId = new InputPortId(node, 0);
                OutputPortId outputId = new OutputPortId(node, 0);
                MaterialKey input = layeredMaterial(layer, lane);
                MaterialKey output = layeredMaterial(layer + 1, lane);
                MaterialSource source = layer == 0
                        ? new MaterialSource.InitialPool(input)
                        : new MaterialSource.ProducerOutput(new OutputPortId(
                                new NodeId((layer - 1) * width + lane), 0));
                nodes.add(new CraftNode(node, new ResourceLocation("test", "layered_" + value),
                        ModType.GENERIC.id(), new ResourceLocation("test", "layered"), 1,
                        List.of(), List.of(), false, null, null,
                        List.of(new InputDemand(inputId, Ingredient.of(input.toStack(1)), 1,
                                DemandRole.CONSUMED, input.toStack(1))),
                        List.of(new OutputDeclaration(outputId, output, 1, OutputKind.PRIMARY))));
                allocations.add(new MaterialAllocation(new AllocationId(value), inputId,
                        source, input, 1));
                if (layer == layers - 1) {
                    roots.add(new RootDemand(Ingredient.of(output.toStack(1)), 1, 0,
                            output.toStack(1), List.of(new RootAllocation(
                            new MaterialSource.ProducerOutput(outputId), output, 1))));
                }
            }
        }
        return new CraftPlanGraph(1, nodes, allocations, roots, List.of(),
                nodes.stream().map(CraftNode::id).toList());
    }

    private static MaterialKey material(int stage) {
        ItemStack stack = new ItemStack(Items.IRON_INGOT);
        var tag = new net.minecraft.nbt.CompoundTag();
        tag.putInt("parallel_stage", stage);
        stack.setTag(tag);
        return MaterialKey.of(stack);
    }

    private static MaterialKey layeredMaterial(int layer, int lane) {
        ItemStack stack = new ItemStack(Items.GOLD_INGOT);
        var tag = new net.minecraft.nbt.CompoundTag();
        tag.putInt("layer", layer);
        tag.putInt("lane", lane);
        stack.setTag(tag);
        return MaterialKey.of(stack);
    }
}
