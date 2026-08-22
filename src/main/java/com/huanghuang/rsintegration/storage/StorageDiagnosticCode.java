package com.huanghuang.rsintegration.storage;

/** Stable, non-sensitive diagnostic categories suitable for logs and network responses. */
public enum StorageDiagnosticCode {
    NONE,
    BACKEND_EXCEPTION,
    PERMISSION_CHECK_FAILED,
    INVALID_NATIVE_RESPONSE,
    NATIVE_LIMIT_EXCEEDED,
    NATIVE_OPERATION_FAILED_AFTER_MUTATION,
    CHANGE_TRACKING_FAILED,
    INSERT_OBSERVER_FAILED,
    INGREDIENT_MATCH_FAILED
}
