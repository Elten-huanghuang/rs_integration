package com.huanghuang.rsintegration.feature.ftbquests;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeSubmissionRecoveryPolicyTest {
    @Test
    void startsRecursiveCraftingWhenNoMaterialWasSubmitted() {
        assertTrue(NativeSubmissionRecoveryPolicy.shouldCraftRemainder(
                false, false, 8L));
    }

    @Test
    void startsRecursiveCraftingForTheRemainderAfterPartialSubmission() {
        assertTrue(NativeSubmissionRecoveryPolicy.shouldCraftRemainder(
                true, true, 6L));
    }

    @Test
    void doesNotCraftRemainderAfterAnUnsafeOrRefundedTransaction() {
        assertFalse(NativeSubmissionRecoveryPolicy.shouldCraftRemainder(
                true, false, 6L));
    }

    @Test
    void doesNotStartRecursiveCraftingWithoutRemainingProgress() {
        assertFalse(NativeSubmissionRecoveryPolicy.shouldCraftRemainder(
                true, true, 0L));
    }
}
