package com.huanghuang.rsintegration.api.consumer;

import java.util.Objects;
import java.util.UUID;

public record ConsumerInspectionTicketId(UUID value) {
    public ConsumerInspectionTicketId {
        Objects.requireNonNull(value, "value");
    }
}
