package com.huanghuang.rsintegration.autoeat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutoEatHungerPolicyTest {

    @Test
    void regularFoodDoesNotIgnoreFullHunger() {
        assertFalse(AutoEatHungerPolicy.canIgnoreFullHunger(false, false));
    }

    @Test
    void alwaysEdibleFoodIgnoresFullHunger() {
        assertTrue(AutoEatHungerPolicy.canIgnoreFullHunger(true, false));
    }

    @Test
    void gnawsGiftLetsRegularFoodIgnoreFullHunger() {
        assertTrue(AutoEatHungerPolicy.canIgnoreFullHunger(false, true));
    }
}
