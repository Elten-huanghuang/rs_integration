package com.huanghuang.rsintegration.client;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientTooltipGuardTest {
    @AfterEach
    void clearGuard() {
        while (ClientTooltipGuard.isRendering()) {
            ClientTooltipGuard.end();
        }
    }

    @Test
    void nestedTooltipScopesRemainActiveUntilTheOuterScopeEnds() {
        ClientTooltipGuard.begin();
        ClientTooltipGuard.begin();
        assertTrue(ClientTooltipGuard.isRendering());

        ClientTooltipGuard.end();
        assertTrue(ClientTooltipGuard.isRendering());

        ClientTooltipGuard.end();
        assertFalse(ClientTooltipGuard.isRendering());
    }

    @Test
    void unmatchedEndCannotForceANegativeDepth() {
        ClientTooltipGuard.end();
        assertFalse(ClientTooltipGuard.isRendering());

        ClientTooltipGuard.begin();
        assertTrue(ClientTooltipGuard.isRendering());
    }
}
