package com.huanghuang.rsintegration.sidepanel;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

final class SidePanelSyncPolicy {
    static final int UNAVAILABLE_RETRY_TICKS = 40;

    private SidePanelSyncPolicy() {}

    static int requestInterval(int configuredInterval, boolean networkAvailable) {
        return networkAvailable
                ? configuredInterval
                : Math.min(configuredInterval, UNAVAILABLE_RETRY_TICKS);
    }

    static boolean shouldRetainCurrentEntries(int currentEntries, int incomingEntries,
                                              boolean networkAvailable) {
        return currentEntries > 0 && incomingEntries == 0 && !networkAvailable;
    }

    static int snapshotLimit(int configuredMaxSlots, int availableEntries) {
        return Math.min(Math.max(configuredMaxSlots, 0), Math.max(availableEntries, 0));
    }

    static void rememberPriority(Map<UUID, Long> priorities, UUID stackId,
                                 long timestamp, int maximum) {
        if (maximum <= 0) {
            priorities.clear();
            return;
        }
        priorities.put(stackId, timestamp);
        while (priorities.size() > maximum) {
            priorities.entrySet().stream()
                    .min(Map.Entry.comparingByValue())
                    .ifPresent(entry -> priorities.remove(entry.getKey(), entry.getValue()));
        }
    }

    static Map<UUID, Long> newestPrioritiesFirst(Map<UUID, Long> priorities) {
        Map<UUID, Long> ordered = new LinkedHashMap<>();
        priorities.entrySet().stream()
                .sorted(Map.Entry.<UUID, Long>comparingByValue().reversed())
                .forEach(entry -> ordered.put(entry.getKey(), entry.getValue()));
        return ordered;
    }
}
