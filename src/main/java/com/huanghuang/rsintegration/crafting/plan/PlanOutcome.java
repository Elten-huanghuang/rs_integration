package com.huanghuang.rsintegration.crafting.plan;

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

    public boolean ready() {
        return this == READY;
    }

    public boolean terminal() {
        return true;
    }
}
