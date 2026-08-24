package com.huanghuang.rsintegration.storage.bd;

import com.huanghuang.rsintegration.storage.StorageBackend;
import com.huanghuang.rsintegration.storage.StorageBackendProvider;

/** Loaded reflectively only when BeyondDimensions is present. */
public final class BeyondDimensionsBackendProvider implements StorageBackendProvider {
    @Override
    public StorageBackend createBackend() {
        return new BeyondDimensionsBackend();
    }
}
