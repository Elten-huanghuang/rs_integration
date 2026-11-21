package com.huanghuang.rsintegration.feature.relics.client;

import com.huanghuang.rsintegration.feature.relics.client.RelicRerollHold.Action;
import com.huanghuang.rsintegration.feature.relics.client.RelicRerollHold.Roll;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RelicRerollHoldTest {
    @Test
    void waitsForStatsAndExperienceEvenWhenQualityHasNotChanged() {
        var hold = new RelicRerollHold();
        assertTrue(hold.press());
        assertEquals(Action.REROLL, hold.tick(roll(0.31, 3, 120), true, 20));
        assertEquals(Action.NONE, hold.tick(roll(0.31, 3, 100), true, 20));
        assertEquals(Action.NONE, hold.tick(roll(0.32, 3, 120), true, 20));
        assertEquals(Action.REROLL, hold.tick(roll(0.32, 3, 100), true, 20));
    }

    @Test
    void neverRerollsFiveStarsEvenWhenSpaceRepeatsOrExperienceRunsOut() {
        var hold = new RelicRerollHold();
        assertTrue(hold.press());
        assertEquals(Action.FULL_QUALITY, hold.tick(roll(1, 10, 40), true, 20));
        assertFalse(hold.press());
        hold.release();
        assertTrue(hold.press());
        assertEquals(Action.REROLL, hold.tick(roll(0.91, 9, 20), true, 20));
        assertEquals(Action.FULL_QUALITY, hold.tick(roll(1, 10, 0), false, 20));
        assertEquals(Action.NONE, hold.tick(roll(1, 10, 0), false, 20));
    }

    @Test
    void releasingAndRepressingDoesNotBypassAnOutstandingRequest() {
        var hold = new RelicRerollHold();
        hold.press();
        assertEquals(Action.REROLL, hold.tick(roll(0.31, 3, 75), true, 20));
        hold.release();
        assertEquals(Action.NONE, hold.tick(roll(0.31, 3, 75), true, 20));
        assertTrue(hold.press());
        hold.release();
        assertEquals(Action.NONE, hold.tick(roll(0.31, 3, 75), true, 20));
        assertEquals(Action.REROLL, hold.tick(roll(0.45, 4, 55), true, 20));
        assertEquals(Action.NONE, hold.tick(roll(0.61, 6, 35), true, 20));
        assertFalse(hold.isActive());
    }

    @Test
    void fiveStarsStopAQueuedTapAsSoonAsTheStatsArrive() {
        var hold = new RelicRerollHold();
        hold.press();
        assertEquals(Action.REROLL, hold.tick(roll(0.91, 9, 40), true, 20));
        hold.release();
        hold.press();
        hold.release();
        assertEquals(Action.FULL_QUALITY, hold.tick(roll(1, 10, 40), true, 20));
        assertEquals(Action.NONE, hold.tick(roll(1, 10, 20), true, 20));
        assertFalse(hold.isActive());
    }

    @Test
    void timeoutAndLockedButtonStopInsteadOfSendingAnotherRequest() {
        var hold = new RelicRerollHold();
        hold.press();
        assertEquals(Action.REROLL, hold.tick(roll(0.31, 3, 20), true, 20));
        for (int i = 0; i < 99; i++) {
            assertEquals(Action.NONE, hold.tick(roll(0.31, 3, 20), true, 20));
        }
        assertEquals(Action.TIMED_OUT, hold.tick(roll(0.31, 3, 20), true, 20));
        assertFalse(hold.press());
        hold.release();
        assertTrue(hold.press());
        assertEquals(Action.UNAVAILABLE, hold.tick(roll(0.42, 4, 0), false, 20));
    }

    @Test
    void leavingTheScreenClearsTheRequestButCannotRearmUntilSpaceIsReleased() {
        var hold = new RelicRerollHold();
        hold.press();
        assertEquals(Action.REROLL, hold.tick(roll(0.31, 3, 40), true, 20));
        hold.clearTarget();
        assertFalse(hold.isActive());
        assertTrue(hold.isHeld());
        assertFalse(hold.press());
        hold.release();
        assertTrue(hold.press());
        assertEquals(Action.REROLL, hold.tick(roll(0.45, 4, 40), true, 20));
    }

    private static Roll roll(double rawStat, int quality, int experienceLevel) {
        return new Roll(Map.of("stat", rawStat), quality, 10, experienceLevel);
    }
}
