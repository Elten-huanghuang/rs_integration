package com.huanghuang.rsintegration.api.execution;

import com.huanghuang.rsintegration.api.machine.ConcurrencyCapabilities;
import com.huanghuang.rsintegration.api.machine.SupportScope;
import net.minecraft.resources.ResourceLocation;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public record ExecutionPlan(
        UUID operationId,
        ResourceLocation recipeId,
        ResourceLocation machineId,
        ResourceLocation recipeIntegrationId,
        ResourceLocation machineIntegrationId,
        ResourceLocation executionIntegrationId,
        long recipeRevision,
        long descriptorRevision,
        String recipeFingerprint,
        String descriptorFingerprint,
        List<InputRoute> itemInputs,
        List<FluidRoute> fluidInputs,
        List<EnergyRoute> energyInputs,
        int expectedTicks,
        List<OutputPort> outputPorts,
        SupportScope supportScope,
        ConcurrencyCapabilities concurrency
) {
    public ExecutionPlan {
        if (operationId == null || recipeId == null || machineId == null
                || recipeIntegrationId == null || machineIntegrationId == null
                || executionIntegrationId == null) {
            throw new IllegalArgumentException(
                    "operation, recipe, machine and integration ids are required");
        }
        itemInputs = itemInputs == null ? List.of() : List.copyOf(itemInputs);
        fluidInputs = fluidInputs == null ? List.of() : List.copyOf(fluidInputs);
        energyInputs = energyInputs == null ? List.of() : List.copyOf(energyInputs);
        outputPorts = outputPorts == null ? List.of() : List.copyOf(outputPorts);
        if (recipeRevision < 0L || descriptorRevision < 0L
                || recipeFingerprint == null || recipeFingerprint.isBlank()
                || descriptorFingerprint == null || descriptorFingerprint.isBlank()
                || expectedTicks < 0 || supportScope == null || concurrency == null) {
            throw new IllegalArgumentException("invalid execution plan");
        }
        validateRoutes(itemInputs, fluidInputs, energyInputs, outputPorts);
    }

    private static void validateRoutes(
            List<InputRoute> itemInputs,
            List<FluidRoute> fluidInputs,
            List<EnergyRoute> energyInputs,
            List<OutputPort> outputPorts
    ) {
        Set<String> inputIds = new HashSet<>();
        for (InputRoute route : itemInputs) {
            if (!inputIds.add(route.entryId())) {
                throw new IllegalArgumentException(
                        "duplicate item route entry: " + route.entryId());
            }
        }
        for (FluidRoute route : fluidInputs) {
            if (!inputIds.add(route.entryId())) {
                throw new IllegalArgumentException(
                        "duplicate fluid route entry: " + route.entryId());
            }
        }
        Set<String> capabilities = new HashSet<>();
        for (EnergyRoute route : energyInputs) {
            if (!capabilities.add(route.capabilityId())) {
                throw new IllegalArgumentException(
                        "duplicate energy route: " + route.capabilityId());
            }
        }
        Set<ResourceLocation> outputIds = new HashSet<>();
        for (OutputPort port : outputPorts) {
            if (!outputIds.add(port.portId())) {
                throw new IllegalArgumentException(
                        "duplicate output route: " + port.portId());
            }
        }
    }
}
