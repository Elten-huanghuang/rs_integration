package com.huanghuang.rsintegration.crafting.planning;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanningProgressOverlayStateTest {
    @Test
    void cardStaysCompact() {
        assertTrue(PlanningProgressOverlay.PANEL_WIDTH <= 196);
        assertTrue(PlanningProgressOverlay.PANEL_HEIGHT <= 27);
        assertTrue(PlanningProgressOverlay.PANEL_Z >= 500);
        assertEquals(0xFF, PlanningProgressOverlay.PANEL_BACKGROUND >>> 24);
        assertEquals(0x00, PlanningProgressOverlay.PANEL_BACKGROUND & 0x00FFFFFF);
    }

    @Test
    void cardUsesTheActionbarPositionAboveTheHotbar() {
        assertEquals(178, PlanningProgressOverlay.panelY(270));
        assertEquals(28, PlanningProgressOverlay.panelY(120));
        assertEquals(65, 270 - PlanningProgressOverlay.PANEL_HEIGHT
                - PlanningProgressOverlay.panelY(270));
    }

    @Test
    void runningAndTerminalStatesHaveDistinctAccents() {
        int running = PlanningProgressOverlay.accent(PlanningProgressSnapshot.State.RUNNING);
        int success = PlanningProgressOverlay.accent(PlanningProgressSnapshot.State.SUCCEEDED);
        int failure = PlanningProgressOverlay.accent(PlanningProgressSnapshot.State.FAILED);

        assertTrue(running != success && success != failure && running != failure);
    }

    @Test
    void elapsedTextIsStableAndCompact() {
        assertEquals("1.8s", PlanningProgressOverlay.formatElapsed(1_825L));
        assertEquals("12s", PlanningProgressOverlay.formatElapsed(12_900L));
    }
}
