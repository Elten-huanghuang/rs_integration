package com.huanghuang.rsintegration.storage;

/** Safe catalog: provider names are strings so optional classes are not linked eagerly. */
public final class StorageBackendDescriptors {
    public static final OptionalStorageBackendDescriptor REFINED_STORAGE =
            new OptionalStorageBackendDescriptor(
                    new StorageBackendId("refinedstorage"),
                    "refinedstorage",
                    "com.huanghuang.rsintegration.storage.rs.RefinedStorageBackendProvider");

    public static final OptionalStorageBackendDescriptor BEYOND_DIMENSIONS =
            new OptionalStorageBackendDescriptor(
                    new StorageBackendId("beyonddimensions"),
                    "beyonddimensions",
                    "com.huanghuang.rsintegration.storage.bd.BeyondDimensionsBackendProvider");

    private StorageBackendDescriptors() {}
}
