package com.huanghuang.rsintegration.storage;

/** Machine-readable outcome; callers must not infer failures from an empty stack alone. */
public enum StorageOperationStatus {
    SUCCESS,
    PARTIAL,
    NOT_FOUND,
    REJECTED,
    DENIED,
    UNAVAILABLE,
    INVALID_REQUEST,
    INVALID_RESPONSE,
    INDETERMINATE,
    FAILED
}
