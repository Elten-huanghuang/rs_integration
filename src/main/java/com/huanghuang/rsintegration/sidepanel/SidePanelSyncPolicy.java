package com.huanghuang.rsintegration.sidepanel;

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
}
