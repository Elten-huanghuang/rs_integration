package com.huanghuang.rsintegration.api.execution;

public enum CancelReason {
    PLAYER_REQUEST,
    OPERATOR_REQUEST,
    SERVER_STOP,
    RECIPE_RELOAD,
    CHUNK_UNLOADED,
    MACHINE_REMOVED,
    DEPENDENCY_FAILED,
    UNKNOWN
}
