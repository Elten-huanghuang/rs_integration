package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.crafting.graph.MachineLeaseRegistry;
import com.huanghuang.rsintegration.crafting.graph.NodeId;
import com.huanghuang.rsintegration.network.binding.AltarBindingRegistry.BoundMachine;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AsyncCraftChainMachineDedupTest extends BootstrapTest {

    @Test
    void nbtInsensitiveGraphMaterialCombinesDifferentVariants() {
        ItemStack sluggish = modifiedAxe("celestial_forge:sluggish");
        ItemStack other = modifiedAxe("celestial_forge:other");
        List<ItemStack> pool = new ArrayList<>(List.of(sluggish, other));

        ItemStack selected = AsyncCraftChain.takeMatching(
                pool, Ingredient.of(new ItemStack(Items.IRON_AXE)), 2, false);

        assertEquals(2, selected.getCount());
        assertTrue(pool.get(0).isEmpty());
        assertTrue(pool.get(1).isEmpty());
    }

    @Test
    void exactGraphMaterialDoesNotCombineDifferentVariants() {
        ItemStack sluggish = modifiedAxe("celestial_forge:sluggish");
        ItemStack other = modifiedAxe("celestial_forge:other");
        List<ItemStack> pool = new ArrayList<>(List.of(sluggish, other));

        ItemStack selected = AsyncCraftChain.takeMatching(
                pool, Ingredient.of(sluggish.copyWithCount(1)), 2, true);

        assertTrue(selected.isEmpty());
        assertEquals(1, pool.get(0).getCount());
        assertEquals(1, pool.get(1).getCount());
    }

    private static ItemStack modifiedAxe(String modifier) {
        ItemStack stack = new ItemStack(Items.IRON_AXE);
        CompoundTag tag = new CompoundTag();
        tag.putString("itemModifier", modifier);
        stack.setTag(tag);
        return stack;
    }

    @Test
    void taglessGraphAllocationUsesIngredientReservation() {
        ItemStack plain = new ItemStack(Items.DIAMOND);
        assertFalse(AsyncCraftChain.requiresExactGraphReservation(Ingredient.of(plain)));

        ItemStack modified = plain.copy();
        CompoundTag tag = new CompoundTag();
        tag.putString("itemModifier", "celestial_forge:sluggish");
        modified.setTag(tag);
        assertTrue(AsyncCraftChain.requiresExactGraphReservation(Ingredient.of(modified)));
    }

    @Test
    void exclusiveMultiExecutionNodeUsesOneSerialWorker() {
        assertTrue(AsyncCraftChain.shouldUseGraphOperationGroup(2, 4));
        assertEquals(1, AsyncCraftChain.graphOperationWorkerCount(2, 4, 3, false, false));
    }

    @Test
    void concurrentMultiExecutionNodeMayUseSeveralWorkers() {
        assertEquals(2, AsyncCraftChain.graphOperationWorkerCount(2, 4, 3, true, false));
    }

    @Test
    void reusableGraphMaterialForcesOneSequentialWorker() {
        assertEquals(1, AsyncCraftChain.graphOperationWorkerCount(8, 8, 4, true, true));
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

    @Test
    void rejectedMachineDoesNotBlockLaterUsableMachine() {
        ResourceLocation overworld = new ResourceLocation("minecraft", "overworld");
        BoundMachine denied = new BoundMachine(overworld, new BlockPos(1, 64, 2),
                ModType.GENERIC, "denied");
        BoundMachine usable = new BoundMachine(overworld, new BlockPos(3, 64, 4),
                ModType.GENERIC, "usable");

        AsyncCraftChain.MachineCandidateSelection selection =
                AsyncCraftChain.filterMachineCandidates(
                        List.of(denied, usable),
                        machine -> true,
                        machine -> machine == usable);

        assertEquals(List.of(usable), selection.usable());
        assertTrue(selection.protectionRejected());
        assertEquals(false, selection.unloadedRejected());
    }

    @Test
    void unloadedMachineDoesNotBlockLaterLoadedMachine() {
        ResourceLocation overworld = new ResourceLocation("minecraft", "overworld");
        BoundMachine unloaded = new BoundMachine(overworld, new BlockPos(1, 64, 2),
                ModType.GENERIC, "unloaded");
        BoundMachine loaded = new BoundMachine(overworld, new BlockPos(3, 64, 4),
                ModType.GENERIC, "loaded");

        AsyncCraftChain.MachineCandidateSelection selection =
                AsyncCraftChain.filterMachineCandidates(
                        List.of(unloaded, loaded),
                        machine -> machine == loaded,
                        machine -> true);

        assertEquals(List.of(loaded), selection.usable());
        assertTrue(selection.unloadedRejected());
        assertEquals(false, selection.protectionRejected());
    }
}
