package com.huanghuang.rsintegration.api.execution;

import com.huanghuang.rsintegration.api.IntegrationNames;

public record InputRoute(
        String entryId,
        int slot,
        String inputGroup,
        long amount
) {
    public InputRoute {
        if (entryId == null || entryId.isBlank()) {
            throw new IllegalArgumentException("entryId is required");
        }
        if (slot < 0 || amount <= 0L) {
            throw new IllegalArgumentException("invalid item route");
        }
        inputGroup = IntegrationNames.requireGroup(inputGroup, "inputGroup");
    }
}
