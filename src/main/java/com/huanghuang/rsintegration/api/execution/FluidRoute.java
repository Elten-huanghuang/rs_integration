package com.huanghuang.rsintegration.api.execution;

import com.huanghuang.rsintegration.api.IntegrationNames;

public record FluidRoute(
        String entryId,
        int tank,
        String inputGroup,
        long amount
) {
    public FluidRoute {
        if (entryId == null || entryId.isBlank()) {
            throw new IllegalArgumentException("entryId is required");
        }
        if (tank < 0 || amount <= 0L) {
            throw new IllegalArgumentException("invalid fluid route");
        }
        inputGroup = IntegrationNames.requireGroup(inputGroup, "inputGroup");
    }
}
