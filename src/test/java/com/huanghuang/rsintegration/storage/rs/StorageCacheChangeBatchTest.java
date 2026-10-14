package com.huanghuang.rsintegration.storage.rs;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StorageCacheChangeBatchTest {
    @Test
    void nestedPickupFlushesEachTouchedCacheOnce() {
        Cache first = new Cache();
        Cache second = new Cache();
        int inserted = StorageCacheChangeBatch.call(() -> {
            assertTrue(StorageCacheChangeBatch.defer(first));
            assertTrue(StorageCacheChangeBatch.defer(first));
            return StorageCacheChangeBatch.call(() -> {
                assertTrue(StorageCacheChangeBatch.defer(second));
                assertFalse(StorageCacheChangeBatch.defer(null));
                return 3;
            });
        });
        assertEquals(3, inserted);
        assertEquals(1, first.flushes);
        assertEquals(1, second.flushes);
        assertFalse(StorageCacheChangeBatch.defer(first));
    }

    private static final class Cache implements com.refinedmods.refinedstorage.api.storage.cache.IStorageCache<Object> {
        private int flushes;

        @Override public void flush() { flushes++; }
        @Override public void invalidate(com.refinedmods.refinedstorage.api.storage.cache.InvalidateCause cause) {}
        @Override public void add(Object stack, int size, boolean rebuilding, boolean batched) {}
        @Override public void remove(Object stack, int size, boolean batched) {}
        @Override public void addListener(com.refinedmods.refinedstorage.api.storage.cache.IStorageCacheListener<Object> listener) {}
        @Override public void removeListener(com.refinedmods.refinedstorage.api.storage.cache.IStorageCacheListener<Object> listener) {}
        @Override public void reAttachListeners() {}
        @Override public void sort() {}
        @Override public com.refinedmods.refinedstorage.api.util.IStackList<Object> getList() { return null; }
        @Override public com.refinedmods.refinedstorage.api.util.IStackList<Object> getCraftablesList() { return null; }
        @Override public java.util.List<com.refinedmods.refinedstorage.api.storage.IStorage<Object>> getStorages() { return java.util.List.of(); }
    }
}
