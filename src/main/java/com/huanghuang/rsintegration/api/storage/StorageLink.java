package com.huanghuang.rsintegration.api.storage;

/** Opaque, permission-checked handle to one player's bound storage network. */
public interface StorageLink {
    String backendId();
    String networkId();
    boolean available();
}
