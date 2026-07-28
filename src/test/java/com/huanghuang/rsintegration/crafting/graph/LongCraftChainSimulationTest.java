package com.huanghuang.rsintegration.crafting.graph;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.crafting.OperationExecutionKernel;
import com.huanghuang.rsintegration.crafting.OperationResourceCoordinator;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
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

/** Runs a long, strictly serial machine chain through the production graph primitives. */
class LongCraftChainSimulationTest extends BootstrapTest {

    @Test
    void fiveHundredTwelveMachineChainCompletesWithoutLeaksOrDeadlocks() {
        final int machineCount = 512;
        CraftPlanGraph graph = buildChain(machineCount);
        DagScheduler scheduler = new DagScheduler(graph);
        MaterialBroker broker = new MaterialBroker();
        NodeAdmissionCoordinator admissions = new NodeAdmissionCoordinator(scheduler, broker);
        MachineLeaseRegistry machines = new MachineLeaseRegistry();
        CaptureLeaseRegistry captures = new CaptureLeaseRegistry();
        OperationResourceCoordinator resources = new OperationResourceCoordinator(machines, captures,
                new OperationBudget(4, machineCount));
        OperationExecutionKernel kernel = new OperationExecutionKernel(resources);
        OperationBudget craftBudget = new OperationBudget(4, machineCount);
        UUID craftId = UUID.randomUUID();

        MaterialKey initialMaterial = material(0);
        MaterialSource initialSource = new MaterialSource.InitialPool(initialMaterial);
        broker.publish(initialSource, initialMaterial, 1);

        List<CraftNode> nodes = graph.nodes();
        for (int index = 0; index < machineCount; index++) {
            NodeId nodeId = nodes.get(index).id();
            assertEquals(DagScheduler.NodeState.READY, scheduler.state(nodeId));
            scheduler.claim(nodeId);

            MaterialSource inputSource = index == 0
                    ? initialSource
                    : new MaterialSource.ProducerOutput(new OutputPortId(new NodeId(index - 1), 0));
            MaterialKey inputMaterial = material(index);
            NodeAdmissionCoordinator.Admission admission = admissions.tryAdmitClaimed(
                    new NodeAdmissionCoordinator.Candidate(nodeId,
                            List.of(new MaterialBroker.Request(inputSource, inputMaterial, 1))));
            assertNotNull(admission, "machine " + index + " could not reserve its input");

            MachineLeaseRegistry.MachineKey machine = new MachineLeaseRegistry.MachineKey(
                    new ResourceLocation("minecraft", "overworld"),
                    new BlockPos(index % 32, 64, index / 32), "chain_machine_" + (index % 3));
            OperationExecutionKernel.Session session = kernel.tryPrepare(
                    craftId, nodeId, 0, craftBudget, machine, null);
            assertNotNull(session, "machine " + index + " could not acquire execution resources");
            assertTrue(session.commit(() -> {
                admissions.commit(admission);
                return true;
            }));
            assertTrue(session.tryStart(() -> true));
            assertEquals(OperationExecutionKernel.CompletionResult.SUCCEEDED,
                    session.complete(() -> true, () -> admissions.settleMaterial(admission)));
            session.close();

            MaterialKey outputMaterial = material(index + 1);
            MaterialSource outputSource = new MaterialSource.ProducerOutput(
                    new OutputPortId(nodeId, 0));
            broker.publish(outputSource, outputMaterial, 1);
            scheduler.succeed(nodeId, dependent -> broker.canReserve(List.of(
                    new MaterialBroker.Request(outputSource, outputMaterial, 1))));
        }

        assertTrue(scheduler.allSucceeded());
        assertTrue(scheduler.isDrained());
        assertEquals(machineCount, scheduler.countSucceeded());
        assertEquals(0, craftBudget.active());
        assertEquals(0, machines.size());
        assertEquals(0, captures.size());
        assertEquals(0, broker.heldBy(new NodeId(machineCount - 1)));
        assertEquals(1, broker.drainAvailableProducerAssets().stream()
                .mapToInt(ItemStack::getCount).sum());
    }

    private static CraftPlanGraph buildChain(int count) {
        List<CraftNode> nodes = new ArrayList<>(count);
        List<MaterialAllocation> allocations = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            NodeId nodeId = new NodeId(index);
            InputPortId inputId = new InputPortId(nodeId, 0);
            OutputPortId outputId = new OutputPortId(nodeId, 0);
            MaterialKey input = material(index);
            MaterialKey output = material(index + 1);
            MaterialSource source = index == 0
                    ? new MaterialSource.InitialPool(input)
                    : new MaterialSource.ProducerOutput(new OutputPortId(new NodeId(index - 1), 0));
            nodes.add(new CraftNode(nodeId, new ResourceLocation("test", "machine_" + index),
                    ModType.GENERIC.id(), new ResourceLocation("test", "machine"), 1,
                    List.of(), List.of(), false, null, null,
                    List.of(new InputDemand(inputId, Ingredient.of(input.toStack(1)), 1,
                            index % 7 == 0 ? DemandRole.CATALYST : DemandRole.CONSUMED,
                            input.toStack(1))),
                    List.of(new OutputDeclaration(outputId, output, 1, OutputKind.PRIMARY))));
            allocations.add(new MaterialAllocation(new AllocationId(index), inputId, source, input, 1));
        }
        NodeId finalNode = new NodeId(count - 1);
        MaterialKey finalMaterial = material(count);
        MaterialSource finalSource = new MaterialSource.ProducerOutput(new OutputPortId(finalNode, 0));
        RootDemand root = new RootDemand(Ingredient.of(finalMaterial.toStack(1)), 1, 0,
                finalMaterial.toStack(1), List.of(new RootAllocation(finalSource, finalMaterial, 1)));
        List<NodeId> order = nodes.stream().map(CraftNode::id).toList();
        return new CraftPlanGraph(1, nodes, allocations, List.of(root), List.of(), order);
    }

    private static MaterialKey material(int stage) {
        ItemStack stack = new ItemStack(Items.IRON_INGOT);
        CompoundTag tag = new CompoundTag();
        tag.putInt("chain_stage", stage);
        stack.setTag(tag);
        return MaterialKey.of(stack);
    }
}
