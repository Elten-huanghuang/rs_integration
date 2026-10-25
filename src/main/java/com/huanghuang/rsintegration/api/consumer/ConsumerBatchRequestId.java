package com.huanghuang.rsintegration.api.consumer;

import java.util.Objects;
import java.util.UUID;

public record ConsumerBatchRequestId(UUID value) {
    public ConsumerBatchRequestId {
        Objects.requireNonNull(value, "value");
    }
}
