package com.huanghuang.rsintegration.api.consumer;

import com.huanghuang.rsintegration.api.IntegrationResourceKey;

public record ConsumerOutputResult(
        IntegrationResourceKey resource,
        long amount,
        ConsumerOutputDestination destination,
        boolean delivered
) {
    public ConsumerOutputResult {
        if (resource == null || amount < 0L || destination == null) {
            throw new IllegalArgumentException(
                    "invalid consumer output result");
        }
    }
}
