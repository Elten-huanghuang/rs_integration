package com.huanghuang.rsintegration.mods.rs;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransientEmptySnapshotGuardTest {
    @Test
    void retainsPopulatedSnapshotOnlyDuringGraceWindow() {
        TransientEmptySnapshotGuard guard = new TransientEmptySnapshotGuard();
        Object view = new Object();

        assertTrue(guard.shouldRetain(view, true, true, 1_000L, 250L));
        assertTrue(guard.shouldRetain(view, true, true, 1_249L, 250L));
        assertFalse(guard.shouldRetain(view, true, true, 1_250L, 250L));
    }

    @Test
    void nonEmptyReplacementClearsPendingWindow() {
        TransientEmptySnapshotGuard guard = new TransientEmptySnapshotGuard();
        Object view = new Object();

        assertTrue(guard.shouldRetain(view, true, true, 1_000L, 250L));
        assertFalse(guard.shouldRetain(view, false, true, 1_050L, 250L));
        assertTrue(guard.shouldRetain(view, true, true, 2_000L, 250L));
    }

    @Test
    void replacementViewGetsItsOwnGraceWindow() {
        TransientEmptySnapshotGuard guard = new TransientEmptySnapshotGuard();
        Object firstView = new Object();
        Object secondView = new Object();

        assertTrue(guard.shouldRetain(firstView, true, true, 1_000L, 250L));
        assertTrue(guard.shouldRetain(secondView, true, true, 1_300L, 250L));
    }

    @Test
    void emptyOrDisabledStateDoesNotRetainAnything() {
        TransientEmptySnapshotGuard guard = new TransientEmptySnapshotGuard();
        Object view = new Object();

        assertFalse(guard.shouldRetain(view, true, false, 1_000L, 250L));
        assertFalse(guard.shouldRetain(view, true, true, 1_000L, 0L));
    }
}
