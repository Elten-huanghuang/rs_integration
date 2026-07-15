package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.crafting.graph.CaptureLeaseRegistry;
import com.huanghuang.rsintegration.crafting.graph.CraftNode;
import com.huanghuang.rsintegration.crafting.graph.CraftPlanGraph;
import com.huanghuang.rsintegration.crafting.graph.DagScheduler;
import com.huanghuang.rsintegration.crafting.graph.MachineLeaseRegistry;
import com.huanghuang.rsintegration.crafting.graph.MaterialBroker;
import com.huanghuang.rsintegration.crafting.graph.MaterialKey;
import com.huanghuang.rsintegration.crafting.graph.MaterialSource;
import com.huanghuang.rsintegration.crafting.graph.NodeAdmissionCoordinator;
import com.huanghuang.rsintegration.crafting.graph.NodeId;
import com.huanghuang.rsintegration.crafting.graph.OperationBudget;
import com.huanghuang.rsintegration.crafting.graph.RootAllocation;
import com.huanghuang.rsintegration.crafting.graph.RootDemand;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphDispatchCompositionTest extends BootstrapTest {

    @Test
    void admissionAndOperationSessionHaveDisjointOwnershipThroughSettlement() {
        Harness harness = new Harness();
        NodeAdmissionCoordinator.Admission admission = harness.admit();

        assertNotNull(admission);
        assertEquals(0, harness.machines.size());
        assertEquals(0, harness.captures.size());
        assertEquals(1, harness.broker.heldBy(harness.node));

        OperationExecutionKernel.Session session = harness.prepare();
        assertNotNull(session);
        assertEquals(1, harness.machines.countOwnedBy(harness.craftId));
        assertEquals(1, harness.craftBudget.active());
        assertTrue(session.commit(() -> {
            harness.admissions.commit(admission);
            return true;
        }));
        assertTrue(session.tryStart(() -> true));
        assertEquals(OperationExecutionKernel.CompletionResult.SUCCEEDED,
                session.complete(() -> true, () -> harness.admissions.settleMaterial(admission)));
        session.close();
        harness.scheduler.succeed(harness.node);

        assertTrue(harness.scheduler.allSucceeded());
        assertEquals(0, harness.broker.heldBy(harness.node));
        assertEquals(0, harness.craftBudget.active());
        assertEquals(0, harness.globalBudget.active());
        assertEquals(0, harness.machines.size());
        assertEquals(0, harness.captures.size());
    }

    @Test
    void machineConflictReleasesMaterialAndCanRetryWithoutConsumingStartBudget() {
        Harness harness = new Harness();
        MachineLeaseRegistry.Lease blocker = harness.machines.tryAcquire(harness.machine,
                new MachineLeaseRegistry.Owner(UUID.randomUUID(), new NodeId(9), 0));
        assertNotNull(blocker);
        NodeAdmissionCoordinator.Admission first = harness.admit();
        assertNotNull(first);

        assertNull(harness.prepare());
        harness.admissions.releaseBeforeDispatch(first);
        assertEquals(DagScheduler.NodeState.READY, harness.scheduler.state(harness.node));
        assertEquals(1, harness.broker.available(harness.source, harness.material));
        assertEquals(0, harness.craftBudget.active());
        assertEquals(0, harness.craftBudget.starts());

        assertTrue(harness.machines.release(blocker));
        NodeAdmissionCoordinator.Admission retry = harness.admit();
        OperationExecutionKernel.Session session = harness.prepare();
        assertNotNull(retry);
        assertNotNull(session);
        session.close();
        harness.admissions.releaseBeforeDispatch(retry);

        assertEquals(0, harness.craftBudget.active());
        assertEquals(0, harness.craftBudget.starts());
        assertEquals(0, harness.machines.size());
    }

    @Test
    void failedCommitRollsBackEveryPreStartOwner() {
        Harness harness = new Harness();
        NodeAdmissionCoordinator.Admission admission = harness.admit();
        OperationExecutionKernel.Session session = harness.prepare();
        assertNotNull(admission);
        assertNotNull(session);

        assertTrue(!session.commit(() -> false));
        session.close();
        harness.admissions.releaseBeforeDispatch(admission);

        assertEquals(0, harness.craftBudget.active());
        assertEquals(0, harness.craftBudget.starts());
        assertEquals(0, harness.globalBudget.active());
        assertEquals(0, harness.machines.size());
        assertEquals(0, harness.broker.heldBy(harness.node));
    }

    private static final class Harness {
        final NodeId node = new NodeId(0);
        final UUID craftId = UUID.randomUUID();
        final MaterialKey material = MaterialKey.of(new ItemStack(Items.IRON_INGOT));
        final MaterialSource source = new MaterialSource.InitialPool(material);
        final MaterialBroker broker = new MaterialBroker();
        final DagScheduler scheduler = new DagScheduler(graph(node));
        final NodeAdmissionCoordinator admissions = new NodeAdmissionCoordinator(scheduler, broker);
        final MachineLeaseRegistry machines = new MachineLeaseRegistry();
        final CaptureLeaseRegistry captures = new CaptureLeaseRegistry();
        final OperationBudget globalBudget = new OperationBudget(1, 16);
        final OperationBudget craftBudget = new OperationBudget(1, 16);
        final OperationExecutionKernel kernel = new OperationExecutionKernel(
                new OperationResourceCoordinator(machines, captures, globalBudget));
        final MachineLeaseRegistry.MachineKey machine = new MachineLeaseRegistry.MachineKey(
                new ResourceLocation("minecraft", "overworld"), new BlockPos(1, 64, 1), "test");

        Harness() {
            broker.publish(source, material, 1);
        }

        NodeAdmissionCoordinator.Admission admit() {
            scheduler.claim(node);
            return admissions.tryAdmitClaimed(new NodeAdmissionCoordinator.Candidate(node,
                    List.of(new MaterialBroker.Request(source, material, 1))));
        }

        OperationExecutionKernel.Session prepare() {
            return kernel.tryPrepare(craftId, node, 0, craftBudget, machine, null);
        }
    }

    private static CraftPlanGraph graph(NodeId nodeId) {
        CraftNode node = new CraftNode(nodeId, new ResourceLocation("test", "node"),
                ModType.GENERIC.id(), new ResourceLocation("minecraft", "crafting"),
                1, List.of(), List.of(), false, null, null, List.of(), List.of());
        MaterialKey target = MaterialKey.of(new ItemStack(Items.STICK));
        return new CraftPlanGraph(1, List.of(node), List.of(),
                List.of(new RootDemand(Ingredient.of(Items.STICK), 1, 0,
                        new ItemStack(Items.STICK), List.of(new RootAllocation(
                        new MaterialSource.InitialPool(target), target, 1)))),
                List.of(), List.of(nodeId));
    }
}
