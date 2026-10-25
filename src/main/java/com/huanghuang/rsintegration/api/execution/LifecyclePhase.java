package com.huanghuang.rsintegration.api.execution;

public enum LifecyclePhase {
    QUEUED,
    PREPARING,
    WAITING_FOR_MACHINE,
    WORKING,
    READY_TO_COLLECT,
    COLLECTING,
    SETTLING,
    SUCCEEDED,
    FAILED,
    CANCELLING,
    CANCELLED
}
