package com.huanghuang.rsintegration.api.machine;

public enum MachineExecutionMode {
    IMMEDIATE_ON_INSERT,
    TICKED_AND_BUSY,
    OUTPUT_DELTA_ONLY,
    CUSTOM
}
