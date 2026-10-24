package com.huanghuang.rsintegration.crafting.plan;

import com.huanghuang.rsintegration.crafting.tree.IngredientKey;

import java.util.List;
import java.util.Map;

/** 规划请求的稳定终态，不再由多个布尔字段和提示文本共同推断。 */
public enum PlanOutcome {
    READY,
    NO_RECIPE,
    MISSING_INPUTS,
    CYCLE,
    BUDGET_EXHAUSTED,
    RUNTIME_UNAVAILABLE,
    STALE,
    CANCELLED;

    public static PlanOutcome infer(
            boolean success, Map<IngredientKey, PlanResponse.Availability> materials,
            List<String> missing, boolean executionBlocked) {
        if (success) return READY;
        if (materials != null && materials.values().stream()
                .anyMatch(availability -> availability != null
                        && availability.missingCount() > 0)) {
            return MISSING_INPUTS;
        }
        if (missing != null && !missing.isEmpty()) return MISSING_INPUTS;
        if (executionBlocked) return RUNTIME_UNAVAILABLE;
        return NO_RECIPE;
    }

    public boolean ready() {
        return this == READY;
    }

    public boolean terminal() {
        return true;
    }
}
