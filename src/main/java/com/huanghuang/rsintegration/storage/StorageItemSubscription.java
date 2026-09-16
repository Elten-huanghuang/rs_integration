package com.huanghuang.rsintegration.storage;

/** Closeable live item-change subscription owned by one resolved storage session. */
public interface StorageItemSubscription extends AutoCloseable {
    /** Cheap validity probe; it must never scan the complete storage inventory. */
    boolean isValid();

    @Override
    void close();
}
