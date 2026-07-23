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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AsyncCraftChainMachineDedupTest {
    @Test
    void exclusiveMultiExecutionNodeUsesOneSerialWorker() {
        assertTrue(AsyncCraftChain.shouldUseGraphOperationGroup(2, 4));
        assertEquals(1, AsyncCraftChain.graphOperationWorkerCount(2, 4, 3, false));
    }

    @Test
    void concurrentMultiExecutionNodeMayUseSeveralWorkers() {
        assertEquals(2, AsyncCraftChain.graphOperationWorkerCount(2, 4, 3, true));
    }

    @Test
    void deduplicatesOnlyMatchingDimensionPositionAndModType() {
        ResourceLocation overworld = new ResourceLocation("minecraft", "overworld");
        BlockPos pos = new BlockPos(1, 64, 2);
        BoundMachine first = new BoundMachine(overworld, pos, ModType.GENERIC, "furnace");
        BoundMachine duplicate = new BoundMachine(overworld, pos, ModType.GENERIC, "furnace");
        BoundMachine otherType = new BoundMachine(overworld, pos, ModType.CUSTOM_GUI, "furnace");

        List<BoundMachine> result = AsyncCraftChain.deduplicateMachines(
                List.of(first, duplicate, otherType));

        assertEquals(2, result.size());
        assertSame(first, result.get(0));
        assertSame(otherType, result.get(1));
    }

    @Test
    void skipsMachineLeasedByAnotherCraft() {
        ResourceLocation overworld = new ResourceLocation("minecraft", "overworld");
        BoundMachine busy = new BoundMachine(overworld, new BlockPos(1, 64, 2),
                ModType.GENERIC, "first");
        BoundMachine idle = new BoundMachine(overworld, new BlockPos(3, 64, 4),
                ModType.GENERIC, "second");
        MachineLeaseRegistry leases = new MachineLeaseRegistry();
        MachineLeaseRegistry.MachineKey busyKey = new MachineLeaseRegistry.MachineKey(
                busy.dim(), busy.pos(), ModType.GENERIC.id());
        leases.tryAcquire(busyKey, new MachineLeaseRegistry.Owner(
                UUID.randomUUID(), new NodeId(0), 0));

        List<BoundMachine> result = AsyncCraftChain.filterUnleasedMachines(
                List.of(busy, idle), leases, ModType.GENERIC.id());

        assertEquals(List.of(idle), result);
    }

    @Test
    void classifiesLeaseAvailabilityWithoutTreatingBusyAsMissing() {
        assertEquals(AsyncCraftChain.MachineLeaseAvailability.NONE_BOUND,
                AsyncCraftChain.classifyLeaseAvailability(0, 0));
        assertEquals(AsyncCraftChain.MachineLeaseAvailability.ALL_LEASED,
                AsyncCraftChain.classifyLeaseAvailability(2, 0));
        assertEquals(AsyncCraftChain.MachineLeaseAvailability.AVAILABLE,
                AsyncCraftChain.classifyLeaseAvailability(2, 1));
    }
}
