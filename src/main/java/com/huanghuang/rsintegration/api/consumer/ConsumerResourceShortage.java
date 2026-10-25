package com.huanghuang.rsintegration.api.consumer;

import com.huanghuang.rsintegration.api.IntegrationResourceKey;

public record ConsumerResourceShortage(
        IntegrationResourceKey resource,
        long required,
        long available
) {
    public ConsumerResourceShortage {
        if (resource == null || required < 0L || available < 0L) {
            throw new IllegalArgumentException("invalid shortage");
        }
    }
}
