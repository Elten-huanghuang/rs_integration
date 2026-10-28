package com.huanghuang.rsintegration.crafting.plan;

/** 可跨版本传递的诊断代码；客户端按此映射本地化文本。 */
public enum IntegrationDiagnosticCode {
    RECIPE_NOT_FOUND("rsi.generic.error.recipe_not_found"),
    RECIPE_EXCLUDED("rsi.plan.failure.recipe_excluded"),
    RECIPE_STAGE_MISSING("rsi.generic.error.recipe_stage_missing"),
    NO_BOUND_MACHINE("rsi.plan.failure.no_bound_machine"),
    SMITHING_BASE_REQUIRED("rsi.generic.error.fa_open_smithing"),
    UNSUPPORTED_RECIPE("rsi.generic.error.unsupported_machine"),
    AUTO_CRAFT_DISABLED("rsi.generic.error.auto_craft_disabled"),
    FOOD_VALUES_UNAVAILABLE("rsi.crockpot.error.food_values"),
    NO_INGREDIENTS("rsi.generic.error.no_ingredients"),
    NETWORK_UNAVAILABLE("rsi.generic.error.network_unavailable"),
    MULTIBLOCK_DISABLED("rsi.generic.error.multiblock_auto_craft_disabled"),
    PLANNER_BUSY("rsi.plan.failure.planner_busy"),
    MAX_CRAFTABLE_UNKNOWN("rsi.plan.failure.max_craftable_unknown"),
    COMPLEXITY_LIMIT("rsi.plan.failure.complexity_limit"),
    PLANNING_TIMEOUT("rsi.plan.failure.planning_timeout"),
    MISSING_MATERIALS("rsi.plan.failure.missing_materials"),
    CYCLIC_DEPENDENCY("rsi.plan.failure.cyclic_dependency"),
    NBT_MISMATCH("rsi.plan.failure.nbt_mismatch"),
    MACHINES_LEASED("rsi.plan.failure.machines_leased"),
    NO_READY_MACHINE("rsi.goety.warn.no_ready_altar"),
    PREREQUISITE_UNAVAILABLE("rsi.plan.failure.prerequisite_unavailable"),
    GRAPH_TOO_LARGE("rsi.plan.failure.graph_too_large"),
    RESPONSE_TOO_LARGE("rsi.plan.failure.response_too_large"),
    INTERNAL_ERROR("rsi.plan.failure.internal_error"),
    PLAN_CANCELLED("rsi.plan.failure.planning_cancelled"),
    STALE_PLAN("rsi.plan.failure.stale"),
    SPECIAL_RECIPE("rsi.plan.failure.special_recipe");

    private final String translationKey;

    IntegrationDiagnosticCode(String translationKey) {
        this.translationKey = translationKey;
    }

    public String translationKey() {
        return translationKey;
    }
}
