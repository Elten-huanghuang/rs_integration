package com.huanghuang.rsintegration.api.consumer;

import java.util.Objects;
import java.util.UUID;

public record ConsumerStorageId(UUID value) {
    public ConsumerStorageId {
        Objects.requireNonNull(value, "value");
    }
}
