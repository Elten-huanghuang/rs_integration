package com.huanghuang.rsintegration.api.machine;

import com.huanghuang.rsintegration.api.IntegrationNames;
import net.minecraft.core.Direction;

import javax.annotation.Nullable;

public record MachineItemPort(
        String portId,
        int slot,
        @Nullable Direction side,
        InputOutputAccess direction,
        ItemPortAccess access,
        @Nullable String inputGroup,
        @Nullable String outputGroup,
        int capacity
) {
    public MachineItemPort {
        if (portId == null || portId.isBlank()) {
            throw new IllegalArgumentException("portId is required");
        }
        if (slot < 0 || capacity < 1 || direction == null || access == null) {
            throw new IllegalArgumentException("invalid item port");
        }
        if (direction == InputOutputAccess.INPUT && outputGroup != null) {
            throw new IllegalArgumentException("input item port cannot define outputGroup");
        }
        if (direction == InputOutputAccess.OUTPUT && inputGroup != null) {
            throw new IllegalArgumentException("output item port cannot define inputGroup");
        }
        if (inputGroup != null) {
            inputGroup = IntegrationNames.requireGroup(inputGroup, "inputGroup");
        }
        if (outputGroup != null) {
            outputGroup = IntegrationNames.requireGroup(outputGroup, "outputGroup");
        }
    }
}
