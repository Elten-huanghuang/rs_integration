package com.huanghuang.rsintegration.network;

/** Policy for optional protection providers whose reflective API cannot be queried. */
final class ProtectionFailurePolicy {
    private ProtectionFailurePolicy() {}

    static boolean allowUnknown() {
        return true;
    }
}
