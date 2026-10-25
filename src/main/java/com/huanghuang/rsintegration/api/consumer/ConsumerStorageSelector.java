package com.huanghuang.rsintegration.api.consumer;

import java.util.Objects;

public sealed interface ConsumerStorageSelector {
    record Default() implements ConsumerStorageSelector {}

    record PlayerInventoryOnly() implements ConsumerStorageSelector {}

    record Explicit(
            ConsumerStorageId storageId
    ) implements ConsumerStorageSelector {
        public Explicit {
            Objects.requireNonNull(storageId, "storageId");
        }
    }
}
