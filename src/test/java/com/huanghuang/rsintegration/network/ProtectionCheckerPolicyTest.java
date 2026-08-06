package com.huanghuang.rsintegration.network;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;

class ProtectionCheckerPolicyTest {
    @Test
    void unknownProviderStateFailsClosed() {
        assertFalse(ProtectionFailurePolicy.permitsUnknown());
    }
}
