package com.huanghuang.rsintegration.api.resonance;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class RSIResonanceServicesTest {
    @AfterEach
    void clearService() {
        RSIResonanceServices.clear();
    }

    @Test
    void installedServiceCanBeLookedUpAndClearedWithLifecycle() {
        RSIResonanceApi service = mock(RSIResonanceApi.class);
        assertFalse(RSIResonanceServices.current().isPresent());

        RSIResonanceServices.install(service);
        assertTrue(RSIResonanceServices.current().isPresent());
        assertSame(service, RSIResonanceServices.current().orElseThrow());

        RSIResonanceServices.clear();
        assertFalse(RSIResonanceServices.current().isPresent());
    }
}
