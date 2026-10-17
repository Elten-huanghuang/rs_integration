package com.huanghuang.rsintegration.mods.casualnessdelight;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DeepFryingBatchIdentityTest {

    @Test
    void inputBufferUsesTheLegacyMaterialIdentityReservedByTheChain() {
        assertEquals("legacy:material:0", DeepFryingPanBatchDelegate.INPUT_ID);
    }
}
