package com.huanghuang.rsintegration.sidepanel;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SidePanelSyncPolicyTest {
    @Test
    void usesConfiguredIntervalWhileNetworkIsAvailable() {
        assertEquals(300, SidePanelSyncPolicy.requestInterval(300, true));
    }

    @Test
    void retriesUnavailableNetworkPromptly() {
        assertEquals(40, SidePanelSyncPolicy.requestInterval(300, false));
        assertEquals(20, SidePanelSyncPolicy.requestInterval(20, false));
    }

    @Test
    void retainsCachedEntriesOnlyForUnavailableEmptySync() {
        assertTrue(SidePanelSyncPolicy.shouldRetainCurrentEntries(256, 0, false));
        assertFalse(SidePanelSyncPolicy.shouldRetainCurrentEntries(256, 0, true));
        assertFalse(SidePanelSyncPolicy.shouldRetainCurrentEntries(0, 0, false));
        assertFalse(SidePanelSyncPolicy.shouldRetainCurrentEntries(256, 1, false));
    }

    @Test
    void capsSnapshotsToConfiguredMaximum() {
        assertEquals(256, SidePanelSyncPolicy.snapshotLimit(256, 3357));
        assertEquals(100, SidePanelSyncPolicy.snapshotLimit(256, 100));
        assertEquals(0, SidePanelSyncPolicy.snapshotLimit(-1, 100));
    }
}
