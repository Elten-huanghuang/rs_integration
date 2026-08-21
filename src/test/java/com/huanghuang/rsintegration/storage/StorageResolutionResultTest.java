package com.huanghuang.rsintegration.storage;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StorageResolutionResultTest {
    @Test
    void failureRetainsItsReasonWithoutInventingASession() {
        StorageResolutionResult result = StorageResolutionResult.failure(StorageResolutionStatus.UNLOADED);

        assertEquals(StorageResolutionStatus.UNLOADED, result.status());
        assertTrue(result.session().isEmpty());
        assertThrows(IllegalArgumentException.class,
                () -> StorageResolutionResult.failure(StorageResolutionStatus.RESOLVED));
    }
}
