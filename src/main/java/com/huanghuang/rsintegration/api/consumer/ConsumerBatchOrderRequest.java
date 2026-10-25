package com.huanghuang.rsintegration.api.consumer;

import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Objects;

public record ConsumerBatchOrderRequest(
        ResourceLocation consumerId,
        ConsumerBatchRequestId batchRequestId,
        List<ConsumerOrderRequest> orders
) {
    public ConsumerBatchOrderRequest {
        if (consumerId == null || batchRequestId == null
                || orders == null || orders.isEmpty()) {
            throw new IllegalArgumentException(
                    "batch order request is incomplete");
        }
        if (orders.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException(
                    "batch orders must not contain null");
        }
        orders = List.copyOf(orders);
        if (orders.stream().anyMatch(order ->
                !consumerId.equals(order.consumerId()))) {
            throw new IllegalArgumentException(
                    "batch orders must use the declared consumer");
        }
    }
}
