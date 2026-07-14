package com.huanghuang.rsintegration.crafting.graph;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Exclusive server-side lease for one physical crafting machine. */
public final class MachineLeaseRegistry {

    public record MachineKey(ResourceLocation dimension, BlockPos position, String logicalType) {
        public MachineKey {
            Objects.requireNonNull(dimension, "dimension");
            Objects.requireNonNull(position, "position");
            Objects.requireNonNull(logicalType, "logicalType");
            position = position.immutable();
        }
    }

    public record Owner(UUID craftId, NodeId nodeId, int operationId) {
        public Owner {
            Objects.requireNonNull(craftId, "craftId");
            Objects.requireNonNull(nodeId, "nodeId");
            if (operationId < 0) throw new IllegalArgumentException("operation id must be non-negative");
        }
    }

    public record Lease(MachineKey machine, Owner owner, long generation) {}

    private final Map<MachineKey, Lease> leases = new HashMap<>();
    private long nextGeneration;

    public Lease tryAcquire(MachineKey machine, Owner owner) {
        Objects.requireNonNull(machine, "machine");
        Objects.requireNonNull(owner, "owner");
        if (leases.containsKey(machine)) return null;
        Lease lease = new Lease(machine, owner, nextGeneration++);
        leases.put(machine, lease);
        return lease;
    }

    public boolean release(Lease lease) {
        if (lease == null) return false;
        Lease current = leases.get(lease.machine());
        if (current == null || current.generation() != lease.generation()
                || !current.owner().equals(lease.owner())) return false;
        leases.remove(lease.machine());
        return true;
    }

    public boolean isLeased(MachineKey machine) {
        return leases.containsKey(machine);
    }

    public int size() {
        return leases.size();
    }

    public void clear() {
        leases.clear();
    }
}
