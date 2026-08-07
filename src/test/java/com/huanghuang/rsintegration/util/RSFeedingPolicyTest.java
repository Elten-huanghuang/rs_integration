package com.huanghuang.rsintegration.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RSFeedingPolicyTest {
    @Test
    void anyAllowsAnyPositiveFoodWhileHungry() {
        assertTrue(RSFeedingPolicy.canFeed(RSFeedingPolicy.HungerRule.ANY, 1, 20, false, false));
    }

    @Test
    void halfUsesHalfNutrition() {
        assertTrue(RSFeedingPolicy.canFeed(RSFeedingPolicy.HungerRule.HALF, 3, 6, false, false));
        assertFalse(RSFeedingPolicy.canFeed(RSFeedingPolicy.HungerRule.HALF, 2, 6, false, false));
    }

    @Test
    void fullRequiresWholeNutritionToFit() {
        assertTrue(RSFeedingPolicy.canFeed(RSFeedingPolicy.HungerRule.FULL, 6, 6, false, false));
        assertFalse(RSFeedingPolicy.canFeed(RSFeedingPolicy.HungerRule.FULL, 5, 6, false, false));
    }

    @Test
    void hurtOverrideAndEmptyHungerAreHandled() {
        assertTrue(RSFeedingPolicy.canFeed(RSFeedingPolicy.HungerRule.FULL, 1, 20, true, true));
        assertFalse(RSFeedingPolicy.canFeed(RSFeedingPolicy.HungerRule.FULL, 1, 20, true, false));
        assertFalse(RSFeedingPolicy.canFeed(RSFeedingPolicy.HungerRule.ANY, 0, 20, false, true));
        assertFalse(RSFeedingPolicy.canFeed(RSFeedingPolicy.HungerRule.ANY, 4, 0, false, true));
    }
}
