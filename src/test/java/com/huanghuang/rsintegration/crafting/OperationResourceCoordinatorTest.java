package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.crafting.graph.CaptureLeaseRegistry;
import com.huanghuang.rsintegration.crafting.graph.MachineLeaseRegistry;
import com.huanghuang.rsintegration.crafting.graph.NodeId;
import com.huanghuang.rsintegration.crafting.graph.OperationBudget;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class OperationResourceCoordinatorTest extends BootstrapTest {

    @Test
    void closingBeforeStartDoesNotConsumeLifetimeBudget() {
        MachineLeaseRegistry machines = new MachineLeaseRegistry();
        CaptureLeaseRegistry captures = new CaptureLeaseRegistry();
        OperationBudget global = new OperationBudget(1, 1);
        OperationBudget craft = new OperationBudget(1, 1);
        OperationResourceCoordinator coordinator = new OperationResourceCoordinator(
                machines, captures, global);

        OperationResourceCoordinator.Scope first = coordinator.tryAcquire(
                UUID.randomUUID(), new NodeId(0), 0, craft, machine(0), null);
        assertNotNull(first);
        first.close();

        assertEquals(0, craft.starts());
        assertEquals(0, global.starts());
        assertNotNull(coordinator.tryAcquire(
                UUID.randomUUID(), new NodeId(1), 0, craft, machine(1), null));
    }

    @Test
    void markedStartConsumesLifetimeBudgetAfterClose() {
        MachineLeaseRegistry machines = new MachineLeaseRegistry();
        CaptureLeaseRegistry captures = new CaptureLeaseRegistry();
        OperationBudget global = new OperationBudget(1, 1);
        OperationBudget craft = new OperationBudget(1, 1);
        OperationResourceCoordinator coordinator = new OperationResourceCoordinator(
                machines, captures, global);
        OperationResourceCoordinator.Scope scope = coordinator.tryAcquire(
                UUID.randomUUID(), new NodeId(0), 0, craft, machine(0), null);

        assertNotNull(scope);
        scope.markStartAttempted();
        scope.close();

        assertEquals(1, craft.starts());
        assertEquals(1, global.starts());
        assertNull(coordinator.tryAcquire(
                UUID.randomUUID(), new NodeId(1), 0, craft, machine(1), null));
    }

    @Test
    void scopeCloseReleasesBudgetMachineAndCaptureExactlyOnce() {
        MachineLeaseRegistry machines = new MachineLeaseRegistry();
        CaptureLeaseRegistry captures = new CaptureLeaseRegistry();
        OperationBudget global = new OperationBudget(1, 4);
        OperationBudget craft = new OperationBudget(1, 4);
        OperationResourceCoordinator coordinator = new OperationResourceCoordinator(
                machines, captures, global);
        MachineLeaseRegistry.MachineKey machine = machine(0);
        OperationResourceCoordinator.CaptureRequest capture = capture(0);

        OperationResourceCoordinator.Scope scope = coordinator.tryAcquire(
                UUID.randomUUID(), new NodeId(0), 0, craft, machine, capture);

        assertNotNull(scope);
        assertEquals(1, machines.size());
        assertEquals(1, captures.size());
        assertEquals(1, craft.active());
        assertEquals(1, global.active());
        scope.close();
        scope.close();
        assertEquals(0, machines.size());
        assertEquals(0, captures.size());
        assertEquals(0, craft.active());
        assertEquals(0, global.active());
    }

    @Test
    void machineConflictRollsBackBothBudgetPermits() {
        MachineLeaseRegistry machines = new MachineLeaseRegistry();
        CaptureLeaseRegistry captures = new CaptureLeaseRegistry();
        OperationBudget global = new OperationBudget(2, 4);
        OperationBudget firstCraft = new OperationBudget(1, 4);
        OperationBudget secondCraft = new OperationBudget(1, 4);
        OperationResourceCoordinator coordinator = new OperationResourceCoordinator(
                machines, captures, global);
        MachineLeaseRegistry.MachineKey machine = machine(0);
        OperationResourceCoordinator.Scope first = coordinator.tryAcquire(
                UUID.randomUUID(), new NodeId(0), 0, firstCraft, machine, null);

        OperationResourceCoordinator.Scope rejected = coordinator.tryAcquire(
                UUID.randomUUID(), new NodeId(1), 0, secondCraft, machine, null);

        assertNotNull(first);
        assertNull(rejected);
        assertEquals(0, secondCraft.active());
        assertEquals(1, global.active());
        assertEquals(1, machines.size());
        first.close();
    }

    @Test
    void supportMachineConflictRollsBackEntireScopeAndBudget() {
        MachineLeaseRegistry machines = new MachineLeaseRegistry();
        CaptureLeaseRegistry captures = new CaptureLeaseRegistry();
        OperationBudget global = new OperationBudget(2, 4);
        OperationBudget firstCraft = new OperationBudget(1, 4);
        OperationBudget secondCraft = new OperationBudget(1, 4);
        OperationResourceCoordinator coordinator = new OperationResourceCoordinator(
                machines, captures, global);
        OperationResourceCoordinator.Scope first = coordinator.tryAcquire(
                UUID.randomUUID(), new NodeId(0), 0, firstCraft, machine(1), null);
        assertNotNull(first);

        OperationResourceCoordinator.Scope rejected = coordinator.tryAcquire(
                UUID.randomUUID(), new NodeId(1), 0, secondCraft,
                List.of(machine(2), machine(1)), null);

        assertNull(rejected);
        assertEquals(1, machines.size());
        assertEquals(0, secondCraft.active());
        assertEquals(1, global.active());
        first.close();
    }

    @Test
    void captureConflictRollsBackMachineAndBudget() {
        MachineLeaseRegistry machines = new MachineLeaseRegistry();
        CaptureLeaseRegistry captures = new CaptureLeaseRegistry();
        OperationBudget global = new OperationBudget(2, 4);
        OperationBudget firstCraft = new OperationBudget(1, 4);
        OperationBudget secondCraft = new OperationBudget(1, 4);
        OperationResourceCoordinator coordinator = new OperationResourceCoordinator(
                machines, captures, global);
        OperationResourceCoordinator.Scope first = coordinator.tryAcquire(
                UUID.randomUUID(), new NodeId(0), 0, firstCraft, machine(0), capture(0));

        OperationResourceCoordinator.Scope rejected = coordinator.tryAcquire(
                UUID.randomUUID(), new NodeId(1), 0, secondCraft, machine(1), capture(0));

        assertNotNull(first);
        assertNull(rejected);
        assertEquals(0, secondCraft.active());
        assertEquals(1, global.active());
        assertEquals(1, machines.size());
        assertEquals(1, captures.size());
        first.close();
    }

    private static MachineLeaseRegistry.MachineKey machine(int x) {
        return new MachineLeaseRegistry.MachineKey(
                new ResourceLocation("minecraft", "overworld"), new BlockPos(x, 64, 0), "test");
    }

    private static OperationResourceCoordinator.CaptureRequest capture(int x) {
        return new OperationResourceCoordinator.CaptureRequest(
                new ResourceLocation("minecraft", "overworld"),
                new AABB(x, 64, 0, x + 1, 65, 1), new ItemStack(Items.DIAMOND));
    }
}
