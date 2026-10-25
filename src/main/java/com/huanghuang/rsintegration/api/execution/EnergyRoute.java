package com.huanghuang.rsintegration.api.execution;

public record EnergyRoute(
        String capabilityId,
        long amount
) {
    public EnergyRoute {
        if (capabilityId == null || capabilityId.isBlank() || amount <= 0L) {
            throw new IllegalArgumentException("invalid energy route");
        }
    }
}
