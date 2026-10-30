package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.crafting.progress.CraftProgressSnapshot;

/**
 * 内部稳定失败原因。业务层只保存该值，进度、日志和用户文案均从它单向投影。
 */
public enum CraftFailureCode {
    NONE("none", false, CraftProgressSnapshot.Reason.NONE),
    WAITING_MATERIALS("waiting_materials", true, CraftProgressSnapshot.Reason.WAITING_MATERIALS),
    MACHINE_BUSY("machine_busy", true, CraftProgressSnapshot.Reason.MACHINE_BUSY),
    CHUNK_UNLOADED("chunk_unloaded", true, CraftProgressSnapshot.Reason.CHUNK_UNLOADED),
    OPERATION_BUDGET("operation_budget", true, CraftProgressSnapshot.Reason.OPERATION_BUDGET),
    RESOURCE_CONFLICT("resource_conflict", true, CraftProgressSnapshot.Reason.RESOURCE_CONFLICT),
    CONTRACT_INCOMPATIBLE("contract_incompatible", false,
            CraftProgressSnapshot.Reason.CONTRACT_INCOMPATIBLE),
    MATERIAL_EXTRACTION_FAILED("material_extraction_failed", true,
            CraftProgressSnapshot.Reason.MATERIAL_EXTRACTION_FAILED),
    START_REJECTED("start_rejected", true, CraftProgressSnapshot.Reason.START_REJECTED),
    OUTPUT_MISSING("output_missing", false, CraftProgressSnapshot.Reason.OUTPUT_MISSING),
    PRODUCTION_TARGET_UNMET("production_target_unmet", false,
            CraftProgressSnapshot.Reason.PRODUCTION_TARGET_UNMET),
    TIMEOUT("timeout", false, CraftProgressSnapshot.Reason.TIMEOUT),
    PLAYER_CANCELLED("player_cancelled", false,
            CraftProgressSnapshot.Reason.PLAYER_CANCELLED),
    PLAYER_OFFLINE("player_offline", false, CraftProgressSnapshot.Reason.PLAYER_OFFLINE),
    SERVER_STOP("server_stop", false, CraftProgressSnapshot.Reason.SERVER_STOP),
    INTERNAL_ERROR("internal_error", false, CraftProgressSnapshot.Reason.INTERNAL_ERROR),
    NETWORK_UNAVAILABLE("network_unavailable", true,
            CraftProgressSnapshot.Reason.NETWORK_UNAVAILABLE),
    NO_BOUND_MACHINE("no_bound_machine", false,
            CraftProgressSnapshot.Reason.NO_BOUND_MACHINE),
    INPUT_CONFLICT("input_conflict", false, CraftProgressSnapshot.Reason.INPUT_CONFLICT),
    SPECIAL_RESOURCE_INSUFFICIENT("special_resource_insufficient", true,
            CraftProgressSnapshot.Reason.SPECIAL_RESOURCE_INSUFFICIENT),
    UNKNOWN("unknown", false, CraftProgressSnapshot.Reason.UNKNOWN);

    private final String code;
    private final boolean retryable;
    private final CraftProgressSnapshot.Reason progressReason;

    CraftFailureCode(String code, boolean retryable,
                     CraftProgressSnapshot.Reason progressReason) {
        this.code = code;
        this.retryable = retryable;
        this.progressReason = progressReason;
    }

    public String code() {
        return code;
    }

    public boolean retryable() {
        return retryable;
    }

    public CraftProgressSnapshot.Reason progressReason() {
        return progressReason;
    }
}
