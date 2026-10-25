package com.huanghuang.rsintegration.api.consumer;

public record ConsumerBatchOrderEntry(
        ConsumerExternalRequestId externalRequestId,
        ConsumerResult<ConsumerOrderReceipt> result
) {
    public ConsumerBatchOrderEntry {
        if (externalRequestId == null || result == null) {
            throw new IllegalArgumentException(
                    "batch order entry is incomplete");
        }
    }
}
