package com.huanghuang.rsintegration.api.consumer;

import net.minecraft.network.chat.Component;

import java.util.Objects;

public record ConsumerStorageOption(
        ConsumerStorageId storageId,
        Component displayName,
        boolean playerInventoryIncluded,
        long revision
) {
    public ConsumerStorageOption {
        Objects.requireNonNull(storageId, "storageId");
        Objects.requireNonNull(displayName, "displayName");
        if (revision < 0L) {
            throw new IllegalArgumentException("revision must be non-negative");
        }
    }
}
