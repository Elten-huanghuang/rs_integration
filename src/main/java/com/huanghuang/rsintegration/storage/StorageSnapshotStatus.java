package com.huanghuang.rsintegration.storage;

/** Outcome domain for read-only snapshot access. */
public enum StorageSnapshotStatus {
    SUCCESS,
    DENIED,
    UNAVAILABLE,
    INVALID_RESPONSE,
    FAILED
}
