package com.huanghuang.rsintegration.mixin.plugin;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;

class SimpleBackupsMixinPluginTest {
    @Test void absentBackupModDoesNotLoadCompatibilityMixin() {
        assertFalse(new RSIntegrationMixinPlugin().shouldApplyMixin(
                "de.melanx.simplebackups.BackupThread",
                "com.huanghuang.rsintegration.mixin.simplebackups.UnifiedDiskBackupMixin"));
    }
}
