package com.huanghuang.rsintegration.storage.rs;

import com.huanghuang.rsintegration.storage.StorageBackend;
import com.huanghuang.rsintegration.storage.StorageBackendProvider;

/** Loaded reflectively only when Refined Storage is present. */
public final class RefinedStorageBackendProvider implements StorageBackendProvider {
    @Override
    public StorageBackend createBackend() {
        return new RefinedStorageBackend();
    }
}
