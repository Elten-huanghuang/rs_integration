package com.huanghuang.rsintegration.api.consumer;

public record ConsumerInspectionOptions(
        int pageSize,
        boolean includeRecursiveSummary
) {
    public ConsumerInspectionOptions {
        if (pageSize <= 0) {
            throw new IllegalArgumentException("pageSize must be positive");
        }
    }
}
