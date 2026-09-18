package com.huanghuang.rsintegration.autoeat;

import net.minecraft.resources.ResourceLocation;

import java.util.HashSet;
import java.util.Set;

/** Pure blacklist bounds/merge rules shared by server storage and tests. */
public final class AutoEatBlacklistPolicy {
    public static final int MAX_SIZE = 4096;

    private AutoEatBlacklistPolicy() {}

    /** Returns null for an oversized update without mutating the existing blacklist. */
    public static Set<ResourceLocation> merge(Set<ResourceLocation> current,
                                              Set<ResourceLocation> added,
                                              Set<ResourceLocation> removed) {
        Set<ResourceLocation> merged = new HashSet<>(current);
        merged.addAll(added);
        merged.removeAll(removed);
        return merged.size() > MAX_SIZE ? null : merged;
    }
}
