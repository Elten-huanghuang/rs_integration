package com.huanghuang.rsintegration.api.machine;

import com.huanghuang.rsintegration.api.IntegrationFingerprints;
import net.minecraft.resources.ResourceLocation;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public record MachineDescriptor(
        ResourceLocation machineId,
        long descriptorRevision,
        Set<ResourceLocation> acceptedProcesses,
        List<MachineItemPort> itemPorts,
        List<MachineFluidPort> fluidPorts,
        List<MachineEnergyPort> energyPorts,
        MachineExecutionMode executionMode,
        boolean multiPreload,
        ConcurrencyCapabilities concurrency,
        SupportScope supportScope,
        boolean hasGui
) {
    public MachineDescriptor {
        if (machineId == null) throw new IllegalArgumentException("machineId is required");
        if (descriptorRevision < 0L) {
            throw new IllegalArgumentException("descriptorRevision must be non-negative");
        }
        acceptedProcesses = acceptedProcesses == null
                ? Set.of() : Set.copyOf(acceptedProcesses);
        itemPorts = itemPorts == null ? List.of() : List.copyOf(itemPorts);
        fluidPorts = fluidPorts == null ? List.of() : List.copyOf(fluidPorts);
        energyPorts = energyPorts == null ? List.of() : List.copyOf(energyPorts);
        if (executionMode == null) throw new IllegalArgumentException("executionMode is required");
        if (concurrency == null) throw new IllegalArgumentException("concurrency is required");
        if (supportScope == null) throw new IllegalArgumentException("supportScope is required");
        if (acceptedProcesses.isEmpty()) {
            throw new IllegalArgumentException("at least one accepted process is required");
        }
        validatePorts(itemPorts, fluidPorts, energyPorts, executionMode);
    }

    public String descriptorFingerprint() {
        return IntegrationFingerprints.machineDescriptor(this);
    }

    private static void validatePorts(
            List<MachineItemPort> itemPorts,
            List<MachineFluidPort> fluidPorts,
            List<MachineEnergyPort> energyPorts,
            MachineExecutionMode executionMode
    ) {
        Set<String> ids = new HashSet<>();
        Set<String> physicalSlots = new HashSet<>();
        for (MachineItemPort port : itemPorts) {
            requireUnique(ids, port.portId(), "portId");
            String physical = "item:" + port.access() + ":" + port.side()
                    + ":" + port.slot();
            requireUnique(physicalSlots, physical, "item physical port");
            if (port.access() == ItemPortAccess.CUSTOM
                    && executionMode != MachineExecutionMode.CUSTOM) {
                throw new IllegalArgumentException(
                        "custom item port requires CUSTOM execution mode");
            }
        }
        for (MachineFluidPort port : fluidPorts) {
            requireUnique(ids, port.portId(), "portId");
            String physical = "fluid:" + port.access() + ":" + port.side()
                    + ":" + port.tank();
            requireUnique(physicalSlots, physical, "fluid physical port");
            if (port.access() == FluidPortAccess.CUSTOM
                    && executionMode != MachineExecutionMode.CUSTOM) {
                throw new IllegalArgumentException(
                        "custom fluid port requires CUSTOM execution mode");
            }
        }
        for (MachineEnergyPort port : energyPorts) {
            String id = "energy:" + port.capabilityId();
            requireUnique(ids, id, "energy port id");
            String physical = "energy:" + port.capabilityId() + ":" + port.side();
            requireUnique(physicalSlots, physical, "energy physical port");
        }
    }

    private static void requireUnique(Set<String> values, String value, String label) {
        if (!values.add(Objects.requireNonNull(value, label))) {
            throw new IllegalArgumentException("duplicate " + label + ": " + value);
        }
    }
}
