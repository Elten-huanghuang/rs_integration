package com.huanghuang.rsintegration.feature.resonance.magic;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ResonantSourceTransferMathTest {
    @Test
    void longTargetSpaceRemainsAvailableAfterIntValueSaturates() {
        assertEquals(Integer.MAX_VALUE, ResonantSourceTransferMath.transferRate(
                Integer.MAX_VALUE, Integer.MAX_VALUE, 21_000_000_000L,
                Long.MAX_VALUE, Integer.MAX_VALUE));
    }

    @Test
    void requestedAmountCapsTheNativeTransferRate() {
        assertEquals(250, ResonantSourceTransferMath.transferRate(
                Integer.MAX_VALUE, Integer.MAX_VALUE, 21_000_000_000L,
                Long.MAX_VALUE, 250));
    }
}
