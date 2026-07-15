package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.crafting.graph.MachineLeaseRegistry;
import com.huanghuang.rsintegration.crafting.graph.NodeId;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OperationServicesTest {

    @Test
    void auditReportsResidualOwnershipAndCleanGeneration() {
        OperationServices services = new OperationServices();
        UUID craftId = UUID.randomUUID();
        MachineLeaseRegistry.Lease lease = services.machines().tryAcquire(
                new MachineLeaseRegistry.MachineKey(
                        new net.minecraft.resources.ResourceLocation("minecraft", "overworld"),
                        new net.minecraft.core.BlockPos(1, 2, 3), "test"),
                new MachineLeaseRegistry.Owner(craftId, new NodeId(0), 0));

        assertFalse(services.audit(1).clean());
        assertEquals(1, services.audit(0).machineLeases());
        assertTrue(services.machines().release(lease));
        assertTrue(services.audit(0).clean());
    }

    @Test
    void newGenerationDoesNotShareBudgetOrRegistries() {
        OperationServices first = new OperationServices();
        OperationServices second = new OperationServices();

        assertNotSame(first.globalBudget(), second.globalBudget());
        assertNotSame(first.machines(), second.machines());
        assertNotSame(first.captures(), second.captures());
    }
}
