package com.huanghuang.rsintegration.api;

public record ResourceAmount(
        IntegrationResourceKey resource,
        long amount
) {
    public ResourceAmount {
        if (resource == null) throw new IllegalArgumentException("resource is required");
        if (amount <= 0L) throw new IllegalArgumentException("amount must be positive");
    }
}
