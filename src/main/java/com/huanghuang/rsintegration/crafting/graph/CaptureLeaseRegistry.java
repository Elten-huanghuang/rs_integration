package com.huanghuang.rsintegration.crafting.graph;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.AABB;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Conservative exclusive leases for overlapping world-output capture regions. */
public final class CaptureLeaseRegistry {

    public record Owner(UUID craftId, NodeId nodeId, int operationId) {
        public Owner {
            Objects.requireNonNull(craftId, "craftId");
            Objects.requireNonNull(nodeId, "nodeId");
            if (operationId < 0) throw new IllegalArgumentException("operation id must be non-negative");
        }
    }

    public record Lease(long id, ResourceLocation dimension, AABB region,
                        MaterialKey expectedMaterial, Owner owner) {}

    private final Map<Long, Lease> leases = new HashMap<>();
    private long nextId;

    public synchronized Lease tryAcquire(ResourceLocation dimension, AABB region,
                            MaterialKey expectedMaterial, Owner owner) {
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(region, "region");
        Objects.requireNonNull(expectedMaterial, "expectedMaterial");
        Objects.requireNonNull(owner, "owner");
        for (Lease existing : leases.values()) {
            if (!existing.dimension().equals(dimension)) continue;
            if (!existing.region().intersects(region)) continue;
            if (mayOverlap(existing.expectedMaterial(), expectedMaterial)) return null;
        }
        Lease lease = new Lease(nextId++, dimension, region, expectedMaterial, owner);
        leases.put(lease.id(), lease);
        return lease;
    }

    public synchronized boolean release(Lease lease) {
        if (lease == null) return false;
        Lease current = leases.get(lease.id());
        if (current == null || !current.equals(lease)) return false;
        leases.remove(lease.id());
        return true;
    }

    public synchronized int size() {
        return leases.size();
    }

    public synchronized int countOwnedBy(UUID craftId) {
        Objects.requireNonNull(craftId, "craftId");
        return (int) leases.values().stream()
                .filter(lease -> lease.owner().craftId().equals(craftId))
                .count();
    }

    public Map<Long, Lease> snapshot() {
        return Map.copyOf(leases);
    }

    public synchronized void clear() {
        leases.clear();
    }

    private static boolean mayOverlap(MaterialKey first, MaterialKey second) {
        if (first.item() != second.item()) return false;
        return first.tag() == null || second.tag() == null || first.tag().equals(second.tag());
    }
}
