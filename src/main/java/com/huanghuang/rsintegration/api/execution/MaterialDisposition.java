package com.huanghuang.rsintegration.api.execution;

public record MaterialDisposition(
        ReservedMaterial material,
        MaterialOutcome outcome
) {
    public MaterialDisposition {
        if (material == null || outcome == null) {
            throw new IllegalArgumentException("material disposition is incomplete");
        }
    }
}
