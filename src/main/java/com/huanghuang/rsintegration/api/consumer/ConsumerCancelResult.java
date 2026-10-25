package com.huanghuang.rsintegration.api.consumer;

public record ConsumerCancelResult(
        ConsumerOrderId orderId,
        ConsumerOrderState state
) {
    public ConsumerCancelResult {
        if (orderId == null || state == null) {
            throw new IllegalArgumentException(
                    "cancel result is incomplete");
        }
    }
}
