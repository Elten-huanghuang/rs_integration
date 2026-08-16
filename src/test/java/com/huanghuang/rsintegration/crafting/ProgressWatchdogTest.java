package com.huanghuang.rsintegration.crafting;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProgressWatchdogTest {
    @Test
    void progressRenewsTheFullIdleWindow() {
        ProgressWatchdog watchdog = new ProgressWatchdog(3);
        assertFalse(watchdog.tick());
        assertFalse(watchdog.tick());
        watchdog.markProgress();

        assertEquals(0, watchdog.idleTicks());
        assertFalse(watchdog.tick());
        assertFalse(watchdog.tick());
        assertFalse(watchdog.tick());
        assertTrue(watchdog.tick());
    }

    @Test
    void rejectsNonPositiveTimeouts() {
        assertThrows(IllegalArgumentException.class, () -> new ProgressWatchdog(0));
    }
}
