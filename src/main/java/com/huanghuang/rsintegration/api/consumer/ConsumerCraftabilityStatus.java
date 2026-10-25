package com.huanghuang.rsintegration.api.consumer;

public enum ConsumerCraftabilityStatus {
    READY,
    MISSING_MATERIALS,
    MACHINE_UNAVAILABLE,
    RECIPE_NOT_FOUND,
    RECIPE_AMBIGUOUS,
    RECIPE_UNSUPPORTED,
    DYNAMICALLY_LOCKED,
    STORAGE_UNAVAILABLE,
    OUTPUT_UNAVAILABLE,
    PLAN_LIMIT_REACHED,
    INTERNAL_ERROR
}
