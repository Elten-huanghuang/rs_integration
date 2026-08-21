package com.huanghuang.rsintegration.storage.rs;

import com.huanghuang.rsintegration.storage.StorageBackendId;

/** RS identifiers kept separate from classes that link the optional native API. */
final class RefinedStorageIds {
    static final StorageBackendId BACKEND = new StorageBackendId("refinedstorage");

    private RefinedStorageIds() {}
}
