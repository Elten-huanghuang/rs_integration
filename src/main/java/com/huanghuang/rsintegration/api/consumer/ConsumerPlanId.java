package com.huanghuang.rsintegration.api.consumer;

import java.util.Objects;
import java.util.UUID;

public record ConsumerPlanId(UUID value) {
    public ConsumerPlanId {
        Objects.requireNonNull(value, "value");
    }
}
