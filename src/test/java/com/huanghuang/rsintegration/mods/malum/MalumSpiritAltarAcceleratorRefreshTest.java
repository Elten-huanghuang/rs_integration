package com.huanghuang.rsintegration.mods.malum;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MalumSpiritAltarAcceleratorRefreshTest {
    @Test
    void refreshesNearbyAcceleratorsAfterProgrammaticPlacement() {
        FakeAltar altar = new FakeAltar();

        assertTrue(MalumBatchDelegate.refreshAccelerators(altar));
        assertTrue(altar.acceleratorsRefreshed);
    }

    @Test
    void missingAcceleratorApiRemainsCompatible() {
        assertFalse(MalumBatchDelegate.refreshAccelerators(new Object()));
    }

    public static final class FakeAltar {
        private boolean acceleratorsRefreshed;

        public void recalibrateAccelerators() {
            acceleratorsRefreshed = true;
        }
    }
}
