package com.huanghuang.rsintegration.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigEditPolicyTest {
    @Test
    void remoteServersOnlyAllowLocalClientPreferences() {
        assertTrue(ConfigEditPolicy.canEdit("client", true, true, false));
        for (String file : List.of("common", "server", "storage")) {
            assertFalse(ConfigEditPolicy.canEdit(file, true, true, false));
        }
    }

    @Test
    void singleplayerAllowsAllLoadedFiles() {
        for (String file : List.of("client", "common", "server", "storage")) {
            assertTrue(ConfigEditPolicy.canEdit(file, true, false, true));
            assertFalse(ConfigEditPolicy.canEdit(file, false, false, true));
        }
    }

    @Test
    void titleScreenCannotEditAWorldThatIsNotRunning() {
        assertTrue(ConfigEditPolicy.canEdit("client", true, false, false));
        assertTrue(ConfigEditPolicy.canEdit("common", true, false, false));
        assertTrue(ConfigEditPolicy.canEdit("storage", true, false, false));
        assertFalse(ConfigEditPolicy.canEdit("server", true, false, false));
        assertFalse(ConfigEditPolicy.canEdit("unknown", true, false, true));
    }
}
