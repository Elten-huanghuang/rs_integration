package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.crafting.graph.MachineLeaseRegistry;
import com.huanghuang.rsintegration.crafting.graph.NodeId;
import com.huanghuang.rsintegration.network.binding.AltarBindingRegistry.BoundMachine;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BrewingMachineConcurrencyTest {
    private static final ModType BREWING = ModType.byId("vanilla_brewing_stand");
    private static final ResourceLocation OVERWORLD = new ResourceLocation("minecraft", "overworld");

    @Test
    void twoBrewingStandsCanRunDifferentOrdersIndependently() {
        BoundMachine first = machine(1, 64, 1);
        BoundMachine second = machine(3, 64, 1);
        MachineLeaseRegistry leases = new MachineLeaseRegistry();

        MachineLeaseRegistry.Lease firstLease = leases.tryAcquire(key(first), owner(1));
        MachineLeaseRegistry.Lease secondLease = leases.tryAcquire(key(second), owner(2));

        assertNotNull(firstLease);
        assertNotNull(secondLease);
        assertEquals(2, leases.size());
        assertTrue(leases.release(firstLease));
        assertTrue(leases.isLeased(key(second)));
        assertTrue(leases.release(secondLease));
        assertEquals(0, leases.size());
    }

    @Test
    void occupiedBrewingStandIsSkippedWhileAnotherRemainsAvailable() {
        BoundMachine busy = machine(1, 64, 1);
        BoundMachine available = machine(3, 64, 1);
        MachineLeaseRegistry leases = new MachineLeaseRegistry();
        MachineLeaseRegistry.Lease lease = leases.tryAcquire(key(busy), owner(7));
        assertNotNull(lease);

        List<BoundMachine> candidates = AsyncCraftChain.filterUnleasedMachines(
                List.of(busy, available), leases, BREWING.id());

        assertEquals(List.of(available), candidates);
        assertTrue(leases.release(lease));
    }

    private static BoundMachine machine(int x, int y, int z) {
        return new BoundMachine(OVERWORLD, new BlockPos(x, y, z), BREWING, "brewing_stand");
    }

    private static MachineLeaseRegistry.MachineKey key(BoundMachine machine) {
        return new MachineLeaseRegistry.MachineKey(machine.dim(), machine.pos(), BREWING.id());
    }

    private static MachineLeaseRegistry.Owner owner(int node) {
        return new MachineLeaseRegistry.Owner(UUID.randomUUID(), new NodeId(node), 0);
    }
}
