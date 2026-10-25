package com.huanghuang.rsintegration.crafting.graph;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
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
            logicalType = normalizeLogicalType(logicalType);
            position = position.immutable();
        }

        private static String normalizeLogicalType(String value) {
            String normalized = value.trim().toLowerCase(Locale.ROOT);
            if (normalized.isEmpty() || normalized.chars().anyMatch(Character::isWhitespace)) {
                throw new IllegalArgumentException("logical machine type must be one stable token");
            }
            return normalized;
        }
    }

    public record Lease(MachineKey machine, OperationId owner, long generation) {}

    private final Map<MachineKey, Lease> leases = new HashMap<>();
    private long nextGeneration;

    public synchronized Lease tryAcquire(MachineKey machine, OperationId owner) {
        Objects.requireNonNull(machine, "machine");
        Objects.requireNonNull(owner, "owner");
        List<Lease> acquired = tryAcquireAll(List.of(machine), owner);
        return acquired == null ? null : acquired.get(0);
    }

    /** Acquire a complete machine/support scope without leaving partial leases. */
    public synchronized List<Lease> tryAcquireAll(List<MachineKey> scope, OperationId owner) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(owner, "owner");
        if (scope.isEmpty()) throw new IllegalArgumentException("machine scope must not be empty");
        List<MachineKey> unique = new ArrayList<>();
        for (MachineKey machine : scope) {
            Objects.requireNonNull(machine, "machine");
            if (unique.contains(machine)) continue;
            if (leases.containsKey(machine)) return null;
            unique.add(machine);
        }
        List<Lease> acquired = new ArrayList<>(unique.size());
        for (MachineKey machine : unique) {
            Lease lease = new Lease(machine, owner, nextGeneration++);
            leases.put(machine, lease);
            acquired.add(lease);
        }
        return List.copyOf(acquired);
    }

    public synchronized void releaseAll(List<Lease> scope) {
        if (scope == null) return;
        for (Lease lease : scope) release(lease);
    }

    public synchronized boolean release(Lease lease) {
        if (lease == null) return false;
        Lease current = leases.get(lease.machine());
        if (current == null || current.generation() != lease.generation()
                || !current.owner().equals(lease.owner())) return false;
        leases.remove(lease.machine());
        return true;
    }

    public synchronized boolean isLeased(MachineKey machine) {
        return leases.containsKey(machine);
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

    public synchronized Map<MachineKey, Lease> snapshot() {
        return Map.copyOf(leases);
    }

    public synchronized void clear() {
        leases.clear();
    }
}
