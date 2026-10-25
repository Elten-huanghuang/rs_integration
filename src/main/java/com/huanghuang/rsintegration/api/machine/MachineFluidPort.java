package com.huanghuang.rsintegration.api.machine;

import com.huanghuang.rsintegration.api.IntegrationNames;
import net.minecraft.core.Direction;

import javax.annotation.Nullable;

public record MachineFluidPort(
        String portId,
        int tank,
        @Nullable Direction side,
        InputOutputAccess direction,
        FluidPortAccess access,
        @Nullable String inputGroup,
        @Nullable String outputGroup,
        long capacity
) {
    public MachineFluidPort {
        if (portId == null || portId.isBlank()) {
            throw new IllegalArgumentException("portId is required");
        }
        if (tank < 0 || capacity < 1L || direction == null || access == null) {
            throw new IllegalArgumentException("invalid fluid port");
        }
        if (direction == InputOutputAccess.INPUT && outputGroup != null) {
            throw new IllegalArgumentException("input fluid port cannot define outputGroup");
        }
        if (direction == InputOutputAccess.OUTPUT && inputGroup != null) {
            throw new IllegalArgumentException("output fluid port cannot define inputGroup");
        }
        if (inputGroup != null) {
            inputGroup = IntegrationNames.requireGroup(inputGroup, "inputGroup");
        }
        if (outputGroup != null) {
            outputGroup = IntegrationNames.requireGroup(outputGroup, "outputGroup");
        }
    }
}
