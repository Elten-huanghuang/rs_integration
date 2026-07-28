package com.huanghuang.rsintegration.mods.touhoulittlemaid;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TlmAltarPowerTopUpTest {

    @Test
    void roundsFractionalPowerShortageUpToWholeItems() {
        assertEquals(0, TlmAltarBatchDelegate.powerItemsNeeded(3.0f, 3.0f));
        assertEquals(1, TlmAltarBatchDelegate.powerItemsNeeded(2.5f, 3.0f));
        assertEquals(3, TlmAltarBatchDelegate.powerItemsNeeded(0.2f, 3.0f));
    }

    @Test
    void rejectsInvalidPowerValues() {
        assertEquals(0, TlmAltarBatchDelegate.powerItemsNeeded(Float.NaN, 3.0f));
        assertEquals(0, TlmAltarBatchDelegate.powerItemsNeeded(0.0f, Float.POSITIVE_INFINITY));
    }
}
