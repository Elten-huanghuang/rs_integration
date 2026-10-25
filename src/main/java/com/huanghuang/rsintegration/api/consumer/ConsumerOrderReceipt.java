package com.huanghuang.rsintegration.api.consumer;

public record ConsumerOrderReceipt(
        ConsumerOrderId orderId,
        ConsumerOrderState state
) {
    public ConsumerOrderReceipt {
        if (orderId == null || state == null) {
            throw new IllegalArgumentException("order receipt is incomplete");
        }
    }
}
