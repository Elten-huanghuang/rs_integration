package com.huanghuang.rsintegration.api.consumer;

import java.util.List;
import java.util.Objects;

public record ConsumerBatchOrderReceipt(
        ConsumerBatchRequestId batchRequestId,
        List<ConsumerBatchOrderEntry> entries
) {
    public ConsumerBatchOrderReceipt {
        if (batchRequestId == null) {
            throw new IllegalArgumentException("batchRequestId is required");
        }
        if (entries != null
                && entries.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException(
                    "batch entries must not contain null");
        }
        entries = entries == null ? List.of() : List.copyOf(entries);
    }
}
