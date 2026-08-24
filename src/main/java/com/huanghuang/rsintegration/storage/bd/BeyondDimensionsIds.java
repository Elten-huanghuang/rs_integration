package com.huanghuang.rsintegration.storage.bd;

import com.huanghuang.rsintegration.storage.StorageBackendId;

/** Backend identifiers kept separate from the optional BD API boundary. */
final class BeyondDimensionsIds {
    static final StorageBackendId BACKEND = new StorageBackendId("beyonddimensions");

    private BeyondDimensionsIds() {}
}
