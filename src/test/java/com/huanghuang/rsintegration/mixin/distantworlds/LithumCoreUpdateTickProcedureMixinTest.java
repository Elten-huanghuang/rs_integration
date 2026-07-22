package com.huanghuang.rsintegration.mixin.distantworlds;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LithumCoreUpdateTickProcedureMixinTest {
    @Test
    void disabledFailureRollIsPrivateAndAlwaysMissesLessThanFailureChecks() throws Exception {
        Method method = LithumCoreUpdateTickProcedureMixin.class
                .getDeclaredMethod("rsi$disabledFailureRoll");
        method.setAccessible(true);
        double roll = (double) method.invoke(null);

        assertTrue(Modifier.isPrivate(method.getModifiers()));
        assertEquals(1.0D, roll);
    }
}
