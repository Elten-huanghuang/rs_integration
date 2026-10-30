package com.huanghuang.rsintegration.api.recipe;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductionCompletionPolicyTest {
    @Test
    void fixedOperationsDeliverWhateverWasActuallyProducedIncludingNothing() {
        ProductionCompletionPolicy policy = ProductionCompletionPolicy.FIXED_OPERATIONS;
        assertFalse(policy.isComplete(4, 4, 1, 3));
        assertTrue(policy.isComplete(4, 0, 3, 3));
        assertFalse(policy.canAttempt(4, 0, 3, 3));
    }

    @Test
    void intermediateQuantityTargetWaitsForActualProductionWithinItsBudget() {
        ProductionCompletionPolicy policy = ProductionCompletionPolicy.TARGET_QUANTITY;
        assertFalse(policy.isComplete(3, 0, 1, 3));
        assertTrue(policy.canAttempt(3, 0, 1, 3));
        assertFalse(policy.canAttempt(3, 2, 3, 3));
        assertFalse(policy.isComplete(3, 2, 3, 3));
        assertTrue(policy.isComplete(3, 3, 2, 3));
        assertFalse(policy.canAttempt(3, 3, 2, 3));
    }
}
