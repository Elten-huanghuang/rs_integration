package com.huanghuang.rsintegration.mods.embers;

import com.huanghuang.rsintegration.util.ModIds;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EreAlchemyDelegateModeTest {

    @Test
    void unknownAlchemyCodeUsesInference() {
        assertTrue(EreAlchemyDelegateMode.shouldUseInference(
                ModIds.ID_EMBERS_ALCHEMY, true, false));
    }

    @Test
    void cachedAlchemyCodeSwitchesRemainingOperationsToDeterministicMode() {
        assertFalse(EreAlchemyDelegateMode.shouldUseInference(
                ModIds.ID_EMBERS_ALCHEMY, true, true));
    }

    @Test
    void otherInferenceDelegatesAreUnaffected() {
        assertTrue(EreAlchemyDelegateMode.shouldUseInference("other", true, true));
        assertFalse(EreAlchemyDelegateMode.shouldUseInference("other", false, false));
    }
}
