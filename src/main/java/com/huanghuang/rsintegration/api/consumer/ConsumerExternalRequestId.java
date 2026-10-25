package com.huanghuang.rsintegration.api.consumer;

import java.util.Objects;
import java.util.UUID;

public record ConsumerExternalRequestId(UUID value) {
    public ConsumerExternalRequestId {
        Objects.requireNonNull(value, "value");
    }
}
