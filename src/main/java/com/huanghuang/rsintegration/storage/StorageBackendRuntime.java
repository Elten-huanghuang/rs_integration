package com.huanghuang.rsintegration.storage;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Owns backend registration for the lifetime of one mod process. */
public final class StorageBackendRuntime {
    @FunctionalInterface
    interface Loader {
        StorageBackendLoadResult load(OptionalStorageBackendDescriptor descriptor,
                                      StorageBackendRegistry registry);
    }

    private final StorageBackendRegistry registry = new StorageBackendRegistry();
    private final Loader loader;
    private final Map<StorageBackendId, StorageBackendLoadResult> loadResults = new LinkedHashMap<>();

    public StorageBackendRuntime() {
        this(StorageBackendLoader::load);
    }

    StorageBackendRuntime(Loader loader) {
        this.loader = Objects.requireNonNull(loader, "loader");
    }

    /** Loads each descriptor at most once and retains its first terminal result. */
    public synchronized StorageBackendLoadResult load(OptionalStorageBackendDescriptor descriptor) {
        Objects.requireNonNull(descriptor, "descriptor");
        StorageBackendLoadResult existing = loadResults.get(descriptor.backendId());
        if (existing != null) return existing;

        StorageBackendLoadResult result;
        try {
            result = Objects.requireNonNull(loader.load(descriptor, registry), "backend load result");
            if (!descriptor.backendId().equals(result.backendId())) {
                result = new StorageBackendLoadResult(
                        descriptor.backendId(), StorageBackendLoadStatus.ID_MISMATCH);
            }
        } catch (RuntimeException | LinkageError e) {
            result = new StorageBackendLoadResult(
                    descriptor.backendId(), StorageBackendLoadStatus.FAILED);
        }
        loadResults.put(descriptor.backendId(), result);
        return result;
    }

    public StorageBackendRegistry registry() {
        return registry;
    }

    public synchronized List<StorageBackendLoadResult> loadResults() {
        return List.copyOf(loadResults.values());
    }
}
