package com.huanghuang.rsintegration.api.machine;

import net.minecraft.core.Direction;

import javax.annotation.Nullable;

public record MachineEnergyPort(
        String capabilityId,
        @Nullable Direction side,
        InputOutputAccess direction,
        long capacity
) {
    public MachineEnergyPort {
        if (capabilityId == null || capabilityId.isBlank()) {
            throw new IllegalArgumentException("capabilityId is required");
        }
        if (capacity < 1L || direction == null) {
            throw new IllegalArgumentException("invalid energy port");
        }
    }
}
