package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.crafting.graph.MachineLeaseRegistry;
import com.huanghuang.rsintegration.network.binding.AltarBindingRegistry.BoundMachine;
import net.minecraft.resources.ResourceLocation;

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
}
