package com.huanghuang.rsintegration.crafting.graph;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NodeAdmissionCoordinatorTest extends BootstrapTest {

    @Test
    void leaseConflictRollsBackMaterialReservationAndClaim() {
        NodeId firstId = new NodeId(0);
        NodeId secondId = new NodeId(1);
        CraftPlanGraph graph = independentGraph(firstId, secondId);
        DagScheduler scheduler = new DagScheduler(graph);
        MaterialBroker broker = new MaterialBroker();
        MachineLeaseRegistry machines = new MachineLeaseRegistry();
        CaptureLeaseRegistry captures = new CaptureLeaseRegistry();
        MaterialKey iron = MaterialKey.of(new ItemStack(Items.IRON_INGOT));
        MaterialSource source = new MaterialSource.InitialPool(iron);
        broker.publish(source, iron, 2);
        MachineLeaseRegistry.MachineKey machine = new MachineLeaseRegistry.MachineKey(
                new ResourceLocation("minecraft", "overworld"), new BlockPos(1, 64, 1), "furnace");
        MachineLeaseRegistry.Lease external = machines.tryAcquire(machine,
                new MachineLeaseRegistry.Owner(UUID.randomUUID(), new NodeId(9), 0));
        NodeAdmissionCoordinator coordinator = new NodeAdmissionCoordinator(
                UUID.randomUUID(), scheduler, broker, machines, captures);

        List<NodeAdmissionCoordinator.Admission> admitted = coordinator.admit(List.of(
                candidate(firstId, source, iron, machine),
                new NodeAdmissionCoordinator.Candidate(secondId,
                        List.of(new MaterialBroker.Request(source, iron, 1)), List.of(), List.of())), 2);

        assertEquals(List.of(secondId), admitted.stream().map(NodeAdmissionCoordinator.Admission::nodeId).toList());
        assertEquals(DagScheduler.NodeState.READY, scheduler.state(firstId));
        assertEquals(1, broker.available(source, iron));
        assertEquals(0, broker.heldBy(firstId));
        assertTrue(machines.release(external));
    }

    @Test
    void claimedNodeCanRetryAfterTransientMachineConflict() {
        NodeId firstId = new NodeId(0);
        NodeId secondId = new NodeId(1);
        DagScheduler scheduler = new DagScheduler(independentGraph(firstId, secondId));
        MaterialBroker broker = new MaterialBroker();
        MachineLeaseRegistry machines = new MachineLeaseRegistry();
        CaptureLeaseRegistry captures = new CaptureLeaseRegistry();
        MaterialKey iron = MaterialKey.of(new ItemStack(Items.IRON_INGOT));
        MaterialSource source = new MaterialSource.InitialPool(iron);
        broker.publish(source, iron, 1);
        MachineLeaseRegistry.MachineKey machine = new MachineLeaseRegistry.MachineKey(
                new ResourceLocation("minecraft", "overworld"), new BlockPos(2, 64, 2), "furnace");
        MachineLeaseRegistry.Lease blocker = machines.tryAcquire(machine,
                new MachineLeaseRegistry.Owner(UUID.randomUUID(), new NodeId(9), 0));
        NodeAdmissionCoordinator coordinator = new NodeAdmissionCoordinator(
                UUID.randomUUID(), scheduler, broker, machines, captures);
        NodeAdmissionCoordinator.Candidate candidate = candidate(firstId, source, iron, machine);

        scheduler.claim(firstId);
        assertNull(coordinator.tryAdmitClaimed(candidate));
        scheduler.releaseClaim(firstId);
        assertTrue(machines.release(blocker));
        scheduler.claim(firstId);
        NodeAdmissionCoordinator.Admission retry = coordinator.tryAdmitClaimed(candidate);

        assertTrue(retry != null);
        coordinator.releaseBeforeDispatch(retry);
        assertEquals(DagScheduler.NodeState.READY, scheduler.state(firstId));
        assertEquals(1, broker.available(source, iron));
        assertEquals(0, machines.size());
    }

    @Test
    void twoIndependentLeavesAreAdmittedCommittedAndSettled() {
        NodeId firstId = new NodeId(0);
        NodeId secondId = new NodeId(1);
        DagScheduler scheduler = new DagScheduler(independentGraph(firstId, secondId));
        MaterialBroker broker = new MaterialBroker();
        MachineLeaseRegistry machines = new MachineLeaseRegistry();
        CaptureLeaseRegistry captures = new CaptureLeaseRegistry();
        MaterialKey iron = MaterialKey.of(new ItemStack(Items.IRON_INGOT));
        MaterialSource source = new MaterialSource.InitialPool(iron);
        broker.publish(source, iron, 2);
        NodeAdmissionCoordinator coordinator = new NodeAdmissionCoordinator(
                UUID.randomUUID(), scheduler, broker, machines, captures);

        List<NodeAdmissionCoordinator.Admission> admitted = coordinator.admit(List.of(
                new NodeAdmissionCoordinator.Candidate(firstId,
                        List.of(new MaterialBroker.Request(source, iron, 1)), List.of(), List.of()),
                new NodeAdmissionCoordinator.Candidate(secondId,
                        List.of(new MaterialBroker.Request(source, iron, 1)), List.of(), List.of())), 2);
        for (NodeAdmissionCoordinator.Admission admission : admitted) {
            coordinator.commit(admission);
            coordinator.succeed(admission);
        }

        assertEquals(2, admitted.size());
        assertTrue(scheduler.allSucceeded());
        assertEquals(0, broker.available(source, iron));
    }

    private static NodeAdmissionCoordinator.Candidate candidate(
            NodeId nodeId, MaterialSource source, MaterialKey material,
            MachineLeaseRegistry.MachineKey machine) {
        return new NodeAdmissionCoordinator.Candidate(nodeId,
                List.of(new MaterialBroker.Request(source, material, 1)),
                List.of(new NodeAdmissionCoordinator.MachineRequest(machine, 0)), List.of());
    }

    private static CraftPlanGraph independentGraph(NodeId firstId, NodeId secondId) {
        CraftNode first = node(firstId, "first");
        CraftNode second = node(secondId, "second");
        MaterialKey target = MaterialKey.of(new ItemStack(Items.STICK));
        return new CraftPlanGraph(1, List.of(first, second), List.of(),
                List.of(new RootDemand(Ingredient.of(Items.STICK), 1, 0,
                        new ItemStack(Items.STICK), List.of(new RootAllocation(
                        new MaterialSource.InitialPool(target), target, 1)))),
                List.of(), List.of(firstId, secondId));
    }

    private static CraftNode node(NodeId nodeId, String path) {
        return new CraftNode(nodeId, new ResourceLocation("test", path), ModType.GENERIC.id(),
                new ResourceLocation("minecraft", "crafting"), 1, List.of(), List.of(),
                false, null, null, List.of(), List.of());
    }
}
