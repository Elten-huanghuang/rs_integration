package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.crafting.graph.MachineLeaseRegistry;
import com.huanghuang.rsintegration.network.binding.AltarBindingRegistry.BoundMachine;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Predicate;

/**
 * Side-effect-free machine candidate planning used by the chain coordinator.
 * Lease checks are read-only; acquiring and releasing leases remains owned by
 * {@link AsyncCraftChain} and the operation resource coordinators.
 */
final class MachineDispatchPlanner {
    private MachineDispatchPlanner() {}

    private record MachineIdentity(ResourceLocation dimension, long packedPos, ModType modType) {}

    enum LeaseAvailability {
        NONE_BOUND,
        ALL_LEASED,
        AVAILABLE
    }

    record CandidateSelection(List<BoundMachine> usable,
                              boolean unloadedRejected,
                              boolean protectionRejected) {}

    static List<BoundMachine> deduplicate(List<BoundMachine> machines) {
        LinkedHashMap<MachineIdentity, BoundMachine> distinct = new LinkedHashMap<>();
        for (BoundMachine machine : machines) {
            MachineIdentity identity = new MachineIdentity(
                    machine.dim(), machine.pos().asLong(), machine.type());
            distinct.putIfAbsent(identity, machine);
        }
        return new ArrayList<>(distinct.values());
    }

    static List<BoundMachine> filterUnleased(List<BoundMachine> machines,
                                             MachineLeaseRegistry leases,
                                             String logicalType) {
        List<BoundMachine> available = new ArrayList<>();
        for (BoundMachine machine : machines) {
            MachineLeaseRegistry.MachineKey key = new MachineLeaseRegistry.MachineKey(
                    machine.dim(), machine.pos(), logicalType);
            if (!leases.isLeased(key)) available.add(machine);
        }
        return available;
    }

    static LeaseAvailability classifyLeaseAvailability(int boundCount, int availableCount) {
        if (boundCount <= 0) return LeaseAvailability.NONE_BOUND;
        if (availableCount <= 0) return LeaseAvailability.ALL_LEASED;
        return LeaseAvailability.AVAILABLE;
    }

    static CandidateSelection filterCandidates(List<BoundMachine> machines,
                                               Predicate<BoundMachine> loaded,
                                               Predicate<BoundMachine> permitted) {
        List<BoundMachine> usable = new ArrayList<>();
        boolean unloadedRejected = false;
        boolean protectionRejected = false;
        for (BoundMachine machine : machines) {
            if (!loaded.test(machine)) {
                unloadedRejected = true;
                continue;
            }
            if (!permitted.test(machine)) {
                protectionRejected = true;
                continue;
            }
            usable.add(machine);
        }
        return new CandidateSelection(List.copyOf(usable), unloadedRejected, protectionRejected);
    }

    static List<BoundMachine> routeCandidates(
            List<BoundMachine> machines, MachineSelectionMode mode,
            @Nullable ResourceLocation dimension, @Nullable BlockPos position) {
        if (machines == null || machines.isEmpty() || mode == null
                || mode == MachineSelectionMode.AUTO) {
            return machines == null ? List.of() : machines;
        }
        if (dimension == null || position == null) {
            return mode == MachineSelectionMode.EXCLUSIVE
                    ? List.of() : machines;
        }
        BoundMachine selected = machines.stream()
                .filter(machine -> machine.dim().equals(dimension)
                        && machine.pos().equals(position))
                .findFirst().orElse(null);
        if (mode == MachineSelectionMode.EXCLUSIVE) {
            return selected == null ? List.of() : List.of(selected);
        }
        if (selected == null || machines.size() < 2
                || machines.get(0) == selected) return machines;
        List<BoundMachine> ordered = new ArrayList<>(machines.size());
        ordered.add(selected);
        for (BoundMachine machine : machines) {
            if (machine != selected) ordered.add(machine);
        }
        return ordered;
    }

    static int combinedParallelDispatchLimit(
            int configuredLimit, List<Integer> physicalCapacities) {
        int baseline = Math.max(1, configuredLimit);
        long combined = 0L;
        for (Integer capacity : physicalCapacities) {
            if (capacity == null || capacity <= 0) continue;
            combined = Math.min(
                    Integer.MAX_VALUE, combined + capacity.longValue());
        }
        return (int) Math.max(baseline, combined);
    }
}
