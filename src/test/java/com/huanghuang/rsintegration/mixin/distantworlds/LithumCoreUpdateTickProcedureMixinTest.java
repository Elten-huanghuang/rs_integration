package com.huanghuang.rsintegration.mixin.distantworlds;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LithumCoreUpdateTickProcedureMixinTest {
    @Test
    void disabledFailureRollAlwaysMissesLessThanFailureChecks() {
        double roll = LithumCoreUpdateTickProcedureMixin.rsi$disabledFailureRoll();

        assertEquals(1.0D, roll);
    }
}