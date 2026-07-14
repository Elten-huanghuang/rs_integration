package com.huanghuang.rsintegration.crafting.graph;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResourceLeaseRegistryTest extends BootstrapTest {

    @Test
    void machineHasOnlyOneOwnerAndStaleLeaseCannotReleaseReplacement() {
        MachineLeaseRegistry registry = new MachineLeaseRegistry();
        MachineLeaseRegistry.MachineKey machine = new MachineLeaseRegistry.MachineKey(
                new ResourceLocation("minecraft", "overworld"), new BlockPos(1, 64, 2), "furnace");
        MachineLeaseRegistry.Owner firstOwner = new MachineLeaseRegistry.Owner(
                UUID.randomUUID(), new NodeId(0), 0);
        MachineLeaseRegistry.Owner secondOwner = new MachineLeaseRegistry.Owner(
                UUID.randomUUID(), new NodeId(1), 0);

        MachineLeaseRegistry.Lease first = registry.tryAcquire(machine, firstOwner);
        assertNotNull(first);
        assertNull(registry.tryAcquire(machine, secondOwner));
        assertTrue(registry.release(first));
        MachineLeaseRegistry.Lease second = registry.tryAcquire(machine, secondOwner);
        assertNotNull(second);
        assertFalse(registry.release(first));
        assertTrue(registry.isLeased(machine));
        assertTrue(registry.release(second));
    }

    @Test
    void overlappingCaptureForSamePossibleOutputIsRejected() {
        CaptureLeaseRegistry registry = new CaptureLeaseRegistry();
        ResourceLocation dimension = new ResourceLocation("minecraft", "overworld");
        MaterialKey iron = MaterialKey.of(new ItemStack(Items.IRON_INGOT));
        CaptureLeaseRegistry.Owner owner = new CaptureLeaseRegistry.Owner(
                UUID.randomUUID(), new NodeId(0), 0);

        CaptureLeaseRegistry.Lease first = registry.tryAcquire(dimension,
                new AABB(0, 0, 0, 2, 2, 2), iron, owner);
        CaptureLeaseRegistry.Lease conflict = registry.tryAcquire(dimension,
                new AABB(1, 1, 1, 3, 3, 3), iron,
                new CaptureLeaseRegistry.Owner(UUID.randomUUID(), new NodeId(1), 0));

        assertNotNull(first);
        assertNull(conflict);
        assertEquals(1, registry.size());
    }

    @Test
    void disjointOrProvablyDifferentCaptureCanProceed() {
        CaptureLeaseRegistry registry = new CaptureLeaseRegistry();
        ResourceLocation dimension = new ResourceLocation("minecraft", "overworld");
        CaptureLeaseRegistry.Owner firstOwner = new CaptureLeaseRegistry.Owner(
                UUID.randomUUID(), new NodeId(0), 0);
        CaptureLeaseRegistry.Owner secondOwner = new CaptureLeaseRegistry.Owner(
                UUID.randomUUID(), new NodeId(1), 0);

        assertNotNull(registry.tryAcquire(dimension, new AABB(0, 0, 0, 2, 2, 2),
                MaterialKey.of(new ItemStack(Items.IRON_INGOT)), firstOwner));
        assertNotNull(registry.tryAcquire(dimension, new AABB(1, 1, 1, 3, 3, 3),
                MaterialKey.of(new ItemStack(Items.GOLD_INGOT)), secondOwner));
        assertNotNull(registry.tryAcquire(dimension, new AABB(10, 10, 10, 11, 11, 11),
                MaterialKey.of(new ItemStack(Items.IRON_INGOT)), secondOwner));
    }
}
