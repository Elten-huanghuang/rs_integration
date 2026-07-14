package com.huanghuang.rsintegration.crafting.graph;

import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.crafting.batch.IBatchDelegate;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Conservative policy gate for cross-node delegate concurrency. */
public final class GraphConcurrencyPolicy {
    private GraphConcurrencyPolicy() {}

    public static boolean isExclusive(String modTypeId, IBatchDelegate delegate) {
        return delegate == null
                || isModDisabled(modTypeId, disabledMods())
                || !delegate.supportsConcurrentNodeExecution();
    }

    static boolean isModDisabled(String modTypeId, List<? extends String> disabled) {
        if (modTypeId == null || disabled == null || disabled.isEmpty()) return false;
        Set<String> normalized = new HashSet<>();
        for (String entry : disabled) {
            if (entry != null && !entry.isBlank()) normalized.add(entry.trim().toLowerCase());
        }
        return normalized.contains(modTypeId.toLowerCase());
    }

    private static List<? extends String> disabledMods() {
        try {
            return RSIntegrationConfig.CRAFTING_PARALLEL_DISABLED_MODS.get();
        } catch (Exception ignored) {
            return List.of();
        }
    }
}
