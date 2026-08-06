package com.huanghuang.rsintegration.network;

/** Policy for an installed protection provider whose API cannot be queried. */
final class ProtectionFailurePolicy {
    private ProtectionFailurePolicy() {}

    static boolean permitsUnknown() {
        return false;
    }
}
