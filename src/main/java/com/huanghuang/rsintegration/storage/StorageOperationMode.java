package com.huanghuang.rsintegration.storage;

/** Whether a result describes a read-only preflight or a real mutation attempt. */
public enum StorageOperationMode {
    SIMULATE,
    PERFORM
}
