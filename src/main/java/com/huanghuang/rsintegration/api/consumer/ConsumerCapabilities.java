package com.huanghuang.rsintegration.api.consumer;

public record ConsumerCapabilities(
        int maxPageSize,
        int maxRecipesPerSession,
        int maxActiveSessionsPerPlayer,
        int maxActiveSessionsGlobal,
        int maxRepeatCount,
        int maxPlanNodes,
        int maxSearchStates,
        int maxOrdersPerBatch,
        int maxConcurrentOrdersPerPlayer,
        long sessionTtlTicks,
        long planTtlTicks,
        long orderIdempotencyRetentionTicks
) {
    public ConsumerCapabilities {
        if (maxPageSize <= 0 || maxRecipesPerSession <= 0
                || maxActiveSessionsPerPlayer <= 0 || maxActiveSessionsGlobal <= 0
                || maxRepeatCount <= 0 || maxPlanNodes <= 0
                || maxSearchStates <= 0 || maxOrdersPerBatch <= 0
                || maxConcurrentOrdersPerPlayer <= 0
                || sessionTtlTicks <= 0L || planTtlTicks <= 0L
                || orderIdempotencyRetentionTicks <= 0L) {
            throw new IllegalArgumentException("consumer limits must be positive");
        }
    }
}
