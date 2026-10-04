package com.huanghuang.rsintegration.compat.jei;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JeiConfigButtonPlacementTest {
    @Test
    void placesConfigAfterBookmarksAndHistory() {
        assertEquals(50, JeiConfigButtonPlacement.x(6, false));
        assertEquals(144, JeiConfigButtonPlacement.x(100, false));
    }

    @Test
    void leavesAnExtraSlotForExtendedAEPlus() {
        assertEquals(72, JeiConfigButtonPlacement.x(6, true));
        assertEquals(166, JeiConfigButtonPlacement.x(100, true));
    }

    @Test
    void narrowBookmarkPanelPlacesConfigOnItsOwnRow() {
        assertFalse(JeiConfigButtonPlacement.needsExtraRow(72, false));
        assertTrue(JeiConfigButtonPlacement.needsExtraRow(72, true));
        assertEquals(new JeiConfigButtonPlacement.Position(6, 192),
                JeiConfigButtonPlacement.position(6, 72, 240, true));
        assertEquals(new JeiConfigButtonPlacement.Position(50, 214),
                JeiConfigButtonPlacement.position(6, 72, 240, false));
        assertEquals(new JeiConfigButtonPlacement.Position(72, 214),
                JeiConfigButtonPlacement.position(6, 94, 240, true));
    }
}
