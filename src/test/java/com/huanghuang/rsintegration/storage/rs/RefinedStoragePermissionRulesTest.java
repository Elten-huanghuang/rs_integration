package com.huanghuang.rsintegration.storage.rs;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RefinedStoragePermissionRulesTest {
    @Test
    void viewRequiresAtLeastOneItemFacingPermission() {
        assertFalse(RefinedStoragePermissionRules.canView(() -> false, () -> false, () -> false));
        assertTrue(RefinedStoragePermissionRules.canView(() -> true, () -> false, () -> false));
        assertTrue(RefinedStoragePermissionRules.canView(() -> false, () -> true, () -> false));
        assertTrue(RefinedStoragePermissionRules.canView(() -> false, () -> false, () -> true));
    }
}
