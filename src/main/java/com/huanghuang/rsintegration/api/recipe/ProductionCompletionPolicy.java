package com.huanghuang.rsintegration.api.recipe;

/**
 * 生产请求的完成规则，与单次机器操作是否成功分别判断。
 *
 * <p>固定次数即“尽力交付”：完成约定次数后交付真实产物，允许全部未命中。
 * 数量目标用于递归供料：必须收齐真实数量，未达到时可在预算内补做。</p>
 */
public enum ProductionCompletionPolicy {
    FIXED_OPERATIONS,
    TARGET_QUANTITY;

    public boolean isComplete(long requested, long produced, int operations, int plannedOperations) {
        return this == FIXED_OPERATIONS
                ? operations >= plannedOperations : produced >= requested;
    }

    public boolean canAttempt(long requested, long produced, int operations, int operationLimit) {
        return operations < operationLimit
                && !isComplete(requested, produced, operations, operationLimit);
    }
}
