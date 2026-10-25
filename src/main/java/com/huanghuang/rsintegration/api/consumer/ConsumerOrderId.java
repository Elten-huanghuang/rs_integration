package com.huanghuang.rsintegration.api.consumer;

import java.util.Objects;
import java.util.UUID;

public record ConsumerOrderId(UUID value) {
    public ConsumerOrderId {
        Objects.requireNonNull(value, "value");
    }
}
