package com.huanghuang.rsintegration.crafting.graph;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;

import java.util.List;
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
    void machineScopeAcquisitionIsAtomic() {
        MachineLeaseRegistry registry = new MachineLeaseRegistry();
        ResourceLocation dimension = new ResourceLocation("minecraft", "overworld");
        MachineLeaseRegistry.MachineKey primary = new MachineLeaseRegistry.MachineKey(
                dimension, new BlockPos(1, 64, 2), "test");
        MachineLeaseRegistry.MachineKey support = new MachineLeaseRegistry.MachineKey(
                dimension, new BlockPos(2, 64, 2), "test:support");
        MachineLeaseRegistry.Lease occupied = registry.tryAcquire(support, machineOwner(0));
        assertNotNull(occupied);

        assertNull(registry.tryAcquireAll(List.of(primary, support), machineOwner(1)));
        assertFalse(registry.isLeased(primary));
        assertEquals(1, registry.size());

        assertTrue(registry.release(occupied));
        List<MachineLeaseRegistry.Lease> scope = registry.tryAcquireAll(
                List.of(primary, support), machineOwner(1));
        assertNotNull(scope);
        assertEquals(2, scope.size());
        registry.releaseAll(scope);
        assertEquals(0, registry.size());
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

    @Test
    void machineKeySeparatesDimensionPositionAndLogicalType() {
        MachineLeaseRegistry registry = new MachineLeaseRegistry();
        MachineLeaseRegistry.Owner owner = machineOwner(0);
        ResourceLocation overworld = new ResourceLocation("minecraft", "overworld");
        ResourceLocation nether = new ResourceLocation("minecraft", "the_nether");
        BlockPos position = new BlockPos(1, 64, 2);

        assertNotNull(registry.tryAcquire(
                new MachineLeaseRegistry.MachineKey(overworld, position, "furnace"), owner));
        assertNotNull(registry.tryAcquire(
                new MachineLeaseRegistry.MachineKey(nether, position, "furnace"), owner));
        assertNotNull(registry.tryAcquire(
                new MachineLeaseRegistry.MachineKey(overworld, position.offset(1, 0, 0), "furnace"), owner));
        assertNotNull(registry.tryAcquire(
                new MachineLeaseRegistry.MachineKey(overworld, position, "smoker"), owner));
        assertEquals(4, registry.size());
    }

    @Test
    void machineReleaseRequiresBothGenerationAndOwner() {
        MachineLeaseRegistry registry = new MachineLeaseRegistry();
        MachineLeaseRegistry.MachineKey machine = new MachineLeaseRegistry.MachineKey(
                new ResourceLocation("minecraft", "overworld"), new BlockPos(1, 64, 2), "furnace");
        MachineLeaseRegistry.Owner owner = machineOwner(0);
        MachineLeaseRegistry.Lease lease = registry.tryAcquire(machine, owner);
        assertNotNull(lease);

        assertFalse(registry.release(new MachineLeaseRegistry.Lease(
                machine, machineOwner(1), lease.generation())));
        assertFalse(registry.release(new MachineLeaseRegistry.Lease(
                machine, owner, lease.generation() + 1)));
        assertTrue(registry.isLeased(machine));
        assertEquals(1, registry.size());
        assertTrue(registry.release(lease));
        assertFalse(registry.release(lease));
        assertEquals(0, registry.size());
    }

    @Test
    void machineClearInvalidatesOldLeaseWithoutAffectingReplacement() {
        MachineLeaseRegistry registry = new MachineLeaseRegistry();
        MachineLeaseRegistry.MachineKey machine = new MachineLeaseRegistry.MachineKey(
                new ResourceLocation("minecraft", "overworld"), new BlockPos(1, 64, 2), "furnace");
        MachineLeaseRegistry.Lease oldLease = registry.tryAcquire(machine, machineOwner(0));
        assertNotNull(oldLease);

        registry.clear();
        assertEquals(0, registry.size());
        MachineLeaseRegistry.Lease replacement = registry.tryAcquire(machine, machineOwner(1));
        assertNotNull(replacement);
        assertFalse(registry.release(oldLease));
        assertTrue(registry.isLeased(machine));
        assertTrue(registry.release(replacement));
        assertEquals(0, registry.size());
    }

    @Test
    void captureDimensionAndBoundaryTouchDoNotConflict() {
        CaptureLeaseRegistry registry = new CaptureLeaseRegistry();
        MaterialKey iron = MaterialKey.of(new ItemStack(Items.IRON_INGOT));
        AABB firstRegion = new AABB(0, 0, 0, 2, 2, 2);

        assertNotNull(registry.tryAcquire(new ResourceLocation("minecraft", "overworld"),
                firstRegion, iron, captureOwner(0)));
        assertNotNull(registry.tryAcquire(new ResourceLocation("minecraft", "the_nether"),
                firstRegion, iron, captureOwner(1)));
        assertNotNull(registry.tryAcquire(new ResourceLocation("minecraft", "overworld"),
                new AABB(2, 0, 0, 4, 2, 2), iron, captureOwner(2)));
        assertEquals(3, registry.size());
    }

    @Test
    void captureNbtPredicatesUseConservativeOverlapRules() {
        CaptureLeaseRegistry registry = new CaptureLeaseRegistry();
        ResourceLocation dimension = new ResourceLocation("minecraft", "overworld");
        AABB region = new AABB(0, 0, 0, 2, 2, 2);
        MaterialKey untagged = MaterialKey.of(new ItemStack(Items.DIAMOND));
        MaterialKey red = taggedDiamond("red");
        MaterialKey blue = taggedDiamond("blue");

        CaptureLeaseRegistry.Lease redLease = registry.tryAcquire(
                dimension, region, red, captureOwner(0));
        assertNotNull(redLease);
        assertNotNull(registry.tryAcquire(dimension, region, blue, captureOwner(1)));
        assertNull(registry.tryAcquire(dimension, region, red, captureOwner(2)));
        assertNull(registry.tryAcquire(dimension, region, untagged, captureOwner(3)));
        assertEquals(2, registry.size());
    }

    @Test
    void captureReleaseChecksCompleteLeaseIdentityAndIsIdempotent() {
        CaptureLeaseRegistry registry = new CaptureLeaseRegistry();
        ResourceLocation dimension = new ResourceLocation("minecraft", "overworld");
        AABB region = new AABB(0, 0, 0, 2, 2, 2);
        MaterialKey iron = MaterialKey.of(new ItemStack(Items.IRON_INGOT));
        CaptureLeaseRegistry.Lease lease = registry.tryAcquire(
                dimension, region, iron, captureOwner(0));
        assertNotNull(lease);

        assertFalse(registry.release(new CaptureLeaseRegistry.Lease(
                lease.id(), dimension, region, iron, captureOwner(1))));
        assertEquals(1, registry.size());
        assertTrue(registry.release(lease));
        assertFalse(registry.release(lease));
        assertEquals(0, registry.size());
    }

    @Test
    void captureClearInvalidatesOldLeaseWithoutAffectingReplacement() {
        CaptureLeaseRegistry registry = new CaptureLeaseRegistry();
        ResourceLocation dimension = new ResourceLocation("minecraft", "overworld");
        AABB region = new AABB(0, 0, 0, 2, 2, 2);
        MaterialKey iron = MaterialKey.of(new ItemStack(Items.IRON_INGOT));
        CaptureLeaseRegistry.Lease oldLease = registry.tryAcquire(
                dimension, region, iron, captureOwner(0));
        assertNotNull(oldLease);

        registry.clear();
        assertEquals(0, registry.size());
        CaptureLeaseRegistry.Lease replacement = registry.tryAcquire(
                dimension, region, iron, captureOwner(1));
        assertNotNull(replacement);
        assertFalse(registry.release(oldLease));
        assertEquals(1, registry.size());
        assertTrue(registry.release(replacement));
        assertEquals(0, registry.size());
    }

    private static MachineLeaseRegistry.Owner machineOwner(int nodeId) {
        return new MachineLeaseRegistry.Owner(UUID.randomUUID(), new NodeId(nodeId), 0);
    }

    private static CaptureLeaseRegistry.Owner captureOwner(int nodeId) {
        return new CaptureLeaseRegistry.Owner(UUID.randomUUID(), new NodeId(nodeId), 0);
    }

    private static MaterialKey taggedDiamond(String variant) {
        ItemStack stack = new ItemStack(Items.DIAMOND);
        CompoundTag tag = new CompoundTag();
        tag.putString("variant", variant);
        stack.setTag(tag);
        return MaterialKey.of(stack);
    }
}
