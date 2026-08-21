package com.huanghuang.rsintegration.storage;

/** Features that must be discovered explicitly instead of inferred from the backend name. */
public enum StorageCapability {
    ITEM_STORAGE,
    CHANGE_TRACKING,
    CRAFTING_STATUS,
    SNAPSHOT_REVISION,
    CHANGE_SUBSCRIPTION
}
