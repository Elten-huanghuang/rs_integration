package com.huanghuang.rsintegration.crafting.graph;

import com.huanghuang.rsintegration.crafting.OperationExecutionKernel;
import com.huanghuang.rsintegration.crafting.OperationResourceCoordinator;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphRuntimeStressTest extends BootstrapTest {

    @Test
    void repeatedMaterialAndOperationLifecyclesLeaveNoOwnershipBehind() {
        final int operations = 500;
        Random random = new Random(0x5EEDC0DEL);
        MaterialBroker broker = new MaterialBroker();
        MaterialKey material = MaterialKey.of(new ItemStack(Items.IRON_INGOT));
        MaterialSource source = new MaterialSource.InitialPool(material);
        broker.publish(source, material, operations);

        MachineLeaseRegistry machines = new MachineLeaseRegistry();
        CaptureLeaseRegistry captures = new CaptureLeaseRegistry();
        OperationBudget globalBudget = new OperationBudget(8, operations);
        OperationResourceCoordinator resources = new OperationResourceCoordinator(
                machines, captures, globalBudget);
        OperationExecutionKernel kernel = new OperationExecutionKernel(resources);
        OperationBudget craftBudget = new OperationBudget(8, operations);
        UUID craftId = UUID.randomUUID();
        NodeId owner = new NodeId(0);
        int expectedAvailable = operations;
        int expectedStarts = 0;

        for (int operation = 0; operation < operations; operation++) {
            MaterialBroker.ReservationToken token = broker.reserve(owner,
                    List.of(new MaterialBroker.Request(source, material, 1)));
            assertNotNull(token);
            expectedAvailable--;

            OperationExecutionKernel.Session session = kernel.tryPrepare(
                    craftId, owner, operation, craftBudget,
                    new MachineLeaseRegistry.MachineKey(
                            new ResourceLocation("minecraft", "overworld"),
                            new BlockPos(operation % 8, 64, operation / 8), "stress"),
                    null);
            assertNotNull(session);

            int outcome = random.nextInt(3);
            if (outcome == 0) {
                broker.release(token);
                expectedAvailable++;
            } else {
                expectedStarts++;
                assertTrue(session.commit(() -> {
                    broker.commit(token);
                    return true;
                }));
                assertTrue(session.tryStart(() -> true));
                if (outcome == 1) {
                    session.complete(() -> true, () -> broker.settle(token));
                } else {
                    broker.refund(token);
                    expectedAvailable++;
                }
            }
            session.close();

            assertEquals(0, craftBudget.active());
            assertEquals(0, globalBudget.active());
            assertEquals(0, machines.size());
            assertEquals(0, captures.size());
            assertEquals(0, broker.heldBy(owner));
            assertEquals(expectedAvailable, broker.available(source, material));
        }

        assertEquals(expectedStarts, craftBudget.starts());
        assertEquals(expectedStarts, globalBudget.starts());
    }
}
