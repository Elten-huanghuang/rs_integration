package com.huanghuang.rsintegration.network;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ProtectionCheckerPolicyTest {
    @Test
    void unknownProviderStateDoesNotImpersonateAnExplicitClaimDenial() {
        assertTrue(ProtectionFailurePolicy.allowUnknown());
    }
}
