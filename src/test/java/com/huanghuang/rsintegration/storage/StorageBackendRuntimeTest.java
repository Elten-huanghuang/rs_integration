package com.huanghuang.rsintegration.storage;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class StorageBackendRuntimeTest {
    private static final StorageBackendId ID = new StorageBackendId("runtime_test");
    private static final OptionalStorageBackendDescriptor DESCRIPTOR =
            new OptionalStorageBackendDescriptor(ID, "runtime_mod", "runtime.Provider");

    @Test
    void descriptorIsLoadedOnlyOnceForTheModLifetime() {
        AtomicInteger calls = new AtomicInteger();
        StorageBackendRuntime runtime = new StorageBackendRuntime((descriptor, registry) -> {
            calls.incrementAndGet();
            return new StorageBackendLoadResult(descriptor.backendId(), StorageBackendLoadStatus.LOADED);
        });

        StorageBackendLoadResult first = runtime.load(DESCRIPTOR);
        StorageBackendLoadResult second = runtime.load(DESCRIPTOR);

        assertSame(first, second);
        assertEquals(1, calls.get());
        assertEquals(java.util.List.of(first), runtime.loadResults());
    }

    @Test
    void loaderFailureIsContainedAndRemainsTerminal() {
        AtomicInteger calls = new AtomicInteger();
        StorageBackendRuntime runtime = new StorageBackendRuntime((descriptor, registry) -> {
            calls.incrementAndGet();
            throw new NoClassDefFoundError("optional backend disappeared");
        });

        assertEquals(StorageBackendLoadStatus.FAILED, runtime.load(DESCRIPTOR).status());
        assertEquals(StorageBackendLoadStatus.FAILED, runtime.load(DESCRIPTOR).status());
        assertEquals(1, calls.get());
    }

    @Test
    void mismatchedLoaderResultCannotPolluteLifecycleState() {
        StorageBackendRuntime runtime = new StorageBackendRuntime((descriptor, registry) ->
                new StorageBackendLoadResult(new StorageBackendId("foreign"),
                        StorageBackendLoadStatus.LOADED));

        StorageBackendLoadResult result = runtime.load(DESCRIPTOR);

        assertEquals(ID, result.backendId());
        assertEquals(StorageBackendLoadStatus.ID_MISMATCH, result.status());
        assertEquals(java.util.List.of(result), runtime.loadResults());
    }
}
