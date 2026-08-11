package com.huanghuang.rsintegration.mods.rs;

import javax.annotation.Nullable;
import java.util.HashSet;
import java.util.Set;

/** Keeps partial search results monotonic until an authoritative scan completes. */
final class ProgressiveSearchResults {
    private ProgressiveSearchResults() {}

    static <T> Set<T> merge(@Nullable Set<T> previous, long previousVersion,
                            Set<T> scanned, long sourceVersion, boolean complete) {
        if (complete || previous == null || previousVersion != sourceVersion) {
            return Set.copyOf(scanned);
        }
        Set<T> merged = new HashSet<>(previous);
        merged.addAll(scanned);
        return Set.copyOf(merged);
    }
}
