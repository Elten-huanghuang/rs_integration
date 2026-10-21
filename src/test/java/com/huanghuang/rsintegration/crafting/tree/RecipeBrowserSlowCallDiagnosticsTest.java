package com.huanghuang.rsintegration.crafting.tree;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecipeBrowserSlowCallDiagnosticsTest {

    @BeforeEach
    @AfterEach
    void resetDiagnostics() {
        RecipeBrowserSlowCallDiagnostics.reset();
    }

    @Test
    void suppressesDuplicateKeysAndCountsThem() {
        assertTrue(RecipeBrowserSlowCallDiagnostics.recordKey("JEI\0layout\0recipe\0class"));
        assertFalse(RecipeBrowserSlowCallDiagnostics.recordKey("JEI\0layout\0recipe\0class"));

        assertEquals(1, RecipeBrowserSlowCallDiagnostics.loggedKeyCount());
        assertEquals(1L, RecipeBrowserSlowCallDiagnostics.suppressedCount());
    }

    @Test
    void keepsOnlyTheConfiguredNumberOfUniqueKeys() {
        for (int i = 0; i < RecipeBrowserSlowCallDiagnostics.MAX_LOGGED_KEYS; i++) {
            assertTrue(RecipeBrowserSlowCallDiagnostics.recordKey("key-" + i));
        }

        assertFalse(RecipeBrowserSlowCallDiagnostics.recordKey("one-key-too-many"));
        assertFalse(RecipeBrowserSlowCallDiagnostics.recordKey("one-key-too-many"));
        assertEquals(RecipeBrowserSlowCallDiagnostics.MAX_LOGGED_KEYS,
                RecipeBrowserSlowCallDiagnostics.loggedKeyCount());
        assertEquals(2L, RecipeBrowserSlowCallDiagnostics.suppressedCount());
    }

    @Test
    void resetClearsKeysSuppressedCountAndSummary() {
        RecipeBrowserSlowCallDiagnostics.recordKey("a");
        RecipeBrowserSlowCallDiagnostics.recordKey("a");
        RecipeBrowserSlowCallDiagnostics.reset();

        assertEquals(0, RecipeBrowserSlowCallDiagnostics.loggedKeyCount());
        assertEquals(0L, RecipeBrowserSlowCallDiagnostics.suppressedCount());
        assertTrue(RecipeBrowserSlowCallDiagnostics.summary().contains("browserSlow=0"));
    }
}
