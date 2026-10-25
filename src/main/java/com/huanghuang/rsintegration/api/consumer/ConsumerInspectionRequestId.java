package com.huanghuang.rsintegration.api.consumer;

import java.util.Objects;
import java.util.UUID;

public record ConsumerInspectionRequestId(UUID value) {
    public ConsumerInspectionRequestId {
        Objects.requireNonNull(value, "value");
    }
}
