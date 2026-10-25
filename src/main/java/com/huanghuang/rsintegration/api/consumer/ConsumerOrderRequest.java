package com.huanghuang.rsintegration.api.consumer;

import net.minecraft.resources.ResourceLocation;

public record ConsumerOrderRequest(
        ResourceLocation consumerId,
        ConsumerExternalRequestId externalRequestId,
        ConsumerPlanId planId
) {
    public ConsumerOrderRequest {
        if (consumerId == null || externalRequestId == null || planId == null) {
            throw new IllegalArgumentException("order request is incomplete");
        }
    }
}
