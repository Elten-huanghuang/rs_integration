package com.huanghuang.rsintegration.crafting.graph;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NodeAdmissionCoordinatorTest extends BootstrapTest {

    @Test
    void materialConflictRollsBackClaimWithoutOwningPhysicalResources() {
        NodeId firstId = new NodeId(0);
        NodeId secondId = new NodeId(1);
        DagScheduler scheduler = new DagScheduler(independentGraph(firstId, secondId));
        MaterialBroker broker = new MaterialBroker();
        MaterialKey iron = MaterialKey.of(new ItemStack(Items.IRON_INGOT));
        MaterialSource source = new MaterialSource.InitialPool(iron);
        broker.publish(source, iron, 1);
        NodeAdmissionCoordinator coordinator = new NodeAdmissionCoordinator(scheduler, broker);

        List<NodeAdmissionCoordinator.Admission> admitted = coordinator.admit(List.of(
                candidate(firstId, source, iron), candidate(secondId, source, iron)), 2);

        assertEquals(1, admitted.size());
        assertEquals(firstId, admitted.get(0).nodeId());
        assertEquals(DagScheduler.NodeState.READY, scheduler.state(secondId));
        assertEquals(0, broker.available(source, iron));
        assertEquals(0, broker.heldBy(secondId));
    }

    @Test
    void claimedNodeCanRetryAfterTransientMaterialConflict() {
        NodeId firstId = new NodeId(0);
        NodeId secondId = new NodeId(1);
        DagScheduler scheduler = new DagScheduler(independentGraph(firstId, secondId));
        MaterialBroker broker = new MaterialBroker();
        MaterialKey iron = MaterialKey.of(new ItemStack(Items.IRON_INGOT));
        MaterialSource source = new MaterialSource.InitialPool(iron);
        broker.publish(source, iron, 1);
        NodeAdmissionCoordinator coordinator = new NodeAdmissionCoordinator(scheduler, broker);

        scheduler.claim(secondId);
        NodeAdmissionCoordinator.Admission blocker = coordinator.tryAdmitClaimed(
                candidate(secondId, source, iron));
        assertTrue(blocker != null);
        scheduler.claim(firstId);
        assertNull(coordinator.tryAdmitClaimed(candidate(firstId, source, iron)));
        scheduler.releaseClaim(firstId);

        coordinator.releaseBeforeDispatch(blocker);
        scheduler.claim(firstId);
        NodeAdmissionCoordinator.Admission retry = coordinator.tryAdmitClaimed(
                candidate(firstId, source, iron));

        assertTrue(retry != null);
        coordinator.releaseBeforeDispatch(retry);
        assertEquals(DagScheduler.NodeState.READY, scheduler.state(firstId));
        assertEquals(1, broker.available(source, iron));
    }

    @Test
    void twoIndependentLeavesAreAdmittedCommittedAndSettled() {
        NodeId firstId = new NodeId(0);
        NodeId secondId = new NodeId(1);
        DagScheduler scheduler = new DagScheduler(independentGraph(firstId, secondId));
        MaterialBroker broker = new MaterialBroker();
        MaterialKey iron = MaterialKey.of(new ItemStack(Items.IRON_INGOT));
        MaterialSource source = new MaterialSource.InitialPool(iron);
        broker.publish(source, iron, 2);
        NodeAdmissionCoordinator coordinator = new NodeAdmissionCoordinator(scheduler, broker);

        List<NodeAdmissionCoordinator.Admission> admitted = coordinator.admit(List.of(
                candidate(firstId, source, iron), candidate(secondId, source, iron)), 2);
        for (NodeAdmissionCoordinator.Admission admission : admitted) {
            coordinator.commit(admission);
            coordinator.succeed(admission);
        }

        assertEquals(2, admitted.size());
        assertTrue(scheduler.allSucceeded());
        assertEquals(0, broker.available(source, iron));
    }

    private static NodeAdmissionCoordinator.Candidate candidate(
            NodeId nodeId, MaterialSource source, MaterialKey material) {
        return new NodeAdmissionCoordinator.Candidate(nodeId,
                List.of(new MaterialBroker.Request(source, material, 1)));
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
