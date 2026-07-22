package com.huanghuang.rsintegration.util;

import java.util.LinkedHashMap;
import java.util.Map;

/** Short-window sampler for recurring operational warnings. */
public final class LogSampler {
    private static final int DEFAULT_MAX_ENTRIES = 4096;

    private final long intervalNanos;
    private final int maxEntries;
    private final Map<String, Long> last;

    public LogSampler(long intervalMillis) {
        this(intervalMillis, DEFAULT_MAX_ENTRIES);
    }

    LogSampler(long intervalMillis, int maxEntries) {
        if (intervalMillis <= 0) throw new IllegalArgumentException("intervalMillis must be positive");
        if (maxEntries <= 0) throw new IllegalArgumentException("maxEntries must be positive");
        this.intervalNanos = intervalMillis * 1_000_000L;
        this.maxEntries = maxEntries;
        this.last = new LinkedHashMap<>(16, 0.75f, true);
    }

    /** Returns true for the first event and once per interval for each key. */
    public synchronized boolean allow(String key) {
        long now = System.nanoTime();
        Long previous = last.get(key);
        if (previous != null && now - previous < intervalNanos) return false;
        last.put(key, now);
        if (last.size() > maxEntries) {
            var iterator = last.entrySet().iterator();
            iterator.next();
            iterator.remove();
        }
        return true;
    }
}
