package com.huanghuang.rsintegration.api.execution;

import com.huanghuang.rsintegration.api.IntegrationResourceKey;
import com.huanghuang.rsintegration.api.recipe.InputConsumption;

public record ReservedMaterial(
        String entryId,
        IntegrationResourceKey resource,
        long amount,
        InputConsumption consumption
) {
    public ReservedMaterial {
        if (entryId == null || entryId.isBlank()
                || resource == null || amount <= 0L || consumption == null) {
            throw new IllegalArgumentException("invalid reserved material");
        }
    }
}
