package com.huanghuang.rsintegration.crafting.plan;

/** 库存未知不等于路线失效；只有确实需要补全或改路时才发起新搜索。 */
enum PlanConfirmationAction {
    CONFIRM(null, false),
    CHECK_STOCK("rsi.plan.inventory.check_confirm", false),
    FIND_ALTERNATIVE("rsi.plan.inventory.missing_confirm", true),
    COMPLETE_ROUTE("rsi.plan.inventory.refresh_confirm", true),
    REBUILD_EXPIRED("rsi.plan.inventory.expired_confirm", true);

    final String labelKey;
    final boolean replan;

    PlanConfirmationAction(String labelKey, boolean replan) {
        this.labelKey = labelKey;
        this.replan = replan;
    }

    static PlanConfirmationAction forInventory(PreviewInventoryModel.Status status) {
        if (status == null) return CONFIRM;
        return switch (status) {
            case READY -> CONFIRM;
            case MISSING -> FIND_ALTERNATIVE;
            case REPLAN -> COMPLETE_ROUTE;
            case UNAVAILABLE -> CHECK_STOCK;
            case EXPIRED -> REBUILD_EXPIRED;
        };
    }
}
