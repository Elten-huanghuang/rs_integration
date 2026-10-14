package com.huanghuang.rsintegration.storage.rs;

import com.refinedmods.refinedstorage.api.storage.cache.IStorageCache;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** 把同一次批量入库的终端通知留到结束再发送。 */
public final class StorageCacheChangeBatch {
    private static final ThreadLocal<ArrayList<Object>> PENDING = new ThreadLocal<>();

    private StorageCacheChangeBatch() {}

    public static <T> T call(Supplier<T> action) {
        if (PENDING.get() != null) return action.get();
        PENDING.set(new ArrayList<>(2));
        try {
            return action.get();
        } finally {
            flush(finish());
        }
    }

    public static boolean defer(Object cache) {
        ArrayList<Object> caches = PENDING.get();
        if (caches == null || cache == null) return false;
        for (int i = 0; i < caches.size(); i++) {
            if (caches.get(i) == cache) return true;
        }
        caches.add(cache);
        return true;
    }

    static List<Object> finish() {
        ArrayList<Object> caches = PENDING.get();
        PENDING.remove();
        return caches == null ? List.of() : List.copyOf(caches);
    }

    @SuppressWarnings("rawtypes")
    private static void flush(List<Object> caches) {
        for (Object cache : caches) ((IStorageCache) cache).flush();
    }
}
