package com.huanghuang.rsintegration.mods.vanilla;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class VanillaMachineBatchDelegateReflectionTest {
    @Test
    void fallsBackToRuntimeFieldNameWithoutTreatingTheFirstMissAsFailure() {
        Field field = VanillaMachineBatchDelegate.findDeclaredField(
                RuntimeFields.class, "developmentName", "runtimeName");

        assertNotNull(field);
        assertEquals("runtimeName", field.getName());
    }

    @Test
    void returnsNullOnlyAfterEveryCandidateMisses() {
        assertNull(VanillaMachineBatchDelegate.findDeclaredField(
                RuntimeFields.class, "developmentName", "otherRuntimeName"));
    }

    private static final class RuntimeFields {
        @SuppressWarnings("unused")
        private int runtimeName;
    }
}
