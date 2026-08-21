package com.huanghuang.rsintegration.storage;

/** Instantiated reflectively only after its optional mod has been confirmed present. */
public interface StorageBackendProvider {
    StorageBackend createBackend();
}
