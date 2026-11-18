package com.huanghuang.rsintegration.crafting;

/** 兼容规划触及资源上限，不能据此断定库存缺料。 */
public final class CraftingPlanningLimitException extends RuntimeException {
    public CraftingPlanningLimitException(String reason) {
        super(reason, null, false, false);
    }
}
