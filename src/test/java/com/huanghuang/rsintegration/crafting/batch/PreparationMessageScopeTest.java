package com.huanghuang.rsintegration.crafting.batch;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PreparationMessageScopeTest {

    @Test
    void nestedScopeRestoresPreviousState() {
        assertFalse(PreparationMessageScope.isSilent());
        PreparationMessageScope.callSilently(() -> {
            assertTrue(PreparationMessageScope.isSilent());
            PreparationMessageScope.callSilently(() -> {
                assertTrue(PreparationMessageScope.isSilent());
                return null;
            });
            assertTrue(PreparationMessageScope.isSilent());
            return null;
        });
        assertFalse(PreparationMessageScope.isSilent());
    }

    @Test
    void exceptionCannotLeakSilentState() {
        assertThrows(IllegalStateException.class, () ->
                PreparationMessageScope.callSilently(() -> {
                    throw new IllegalStateException("test");
                }));
        assertFalse(PreparationMessageScope.isSilent());
    }
}
