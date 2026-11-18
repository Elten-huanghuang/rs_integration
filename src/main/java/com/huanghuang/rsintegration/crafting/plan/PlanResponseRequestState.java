package com.huanghuang.rsintegration.crafting.plan;

import java.util.Objects;

/** 关联最新预览及延迟刷新，未展示有效的新计划前不允许执行。 */
final class PlanResponseRequestState {
    enum Confirmation {
        WAIT, RECALCULATE, EXECUTE, BLOCKED
    }

    private long activeRequestId;
    private String expectedRecipeId;
    private boolean pending;
    private boolean refreshScheduled;
    private boolean retryRequired;

    PlanResponseRequestState(long requestId, String recipeId) {
        activeRequestId = requestId;
        expectedRecipeId = recipeId;
    }

    /** 返回本页面被替代的未完成请求，其他页面的任务不受影响。 */
    long beginPreview(long requestId, String recipeId) {
        long supersededRequestId = pending && activeRequestId != requestId ? activeRequestId : 0L;
        activeRequestId = requestId;
        expectedRecipeId = recipeId;
        pending = true;
        refreshScheduled = false;
        retryRequired = false;
        return supersededRequestId;
    }

    void scheduleRefresh() {
        refreshScheduled = true;
    }

    boolean refreshScheduled() {
        return refreshScheduled;
    }

    boolean refreshRequired() {
        return refreshScheduled || retryRequired;
    }

    boolean waitingForPreview() {
        return pending || refreshRequired();
    }

    /** 失败后允许修改路线和重试，确认执行仍须等待有效的新预览。 */
    boolean calculating() {
        return pending || refreshScheduled;
    }

    boolean failPreview(long requestId) {
        if (!pending || requestId != activeRequestId) return false;
        pending = false;
        requireRetry();
        return true;
    }

    /** 失败响应可以保留旧树供查看，但旧树不能用于执行。 */
    void requireRetry() {
        retryRequired = true;
    }

    Confirmation confirmation(boolean readOnly, boolean inventoryRequiresReplan, boolean executable) {
        if (readOnly) return Confirmation.BLOCKED;
        if (refreshScheduled) return Confirmation.RECALCULATE;
        if (pending) return Confirmation.WAIT;
        if (retryRequired || inventoryRequiresReplan) return Confirmation.RECALCULATE;
        return executable ? Confirmation.EXECUTE : Confirmation.BLOCKED;
    }

    boolean accept(long requestId, String recipeId) {
        // 配方相同也可能是独立任务；只有本页面发起的请求才能更新页面。
        if (requestId != activeRequestId) return false;
        boolean error = recipeId == null || recipeId.isEmpty();
        if (!error && !Objects.equals(recipeId, expectedRecipeId)) return false;
        pending = false;
        retryRequired = error;
        return true;
    }

    long activeRequestId() {
        return activeRequestId;
    }

    String expectedRecipeId() {
        return expectedRecipeId;
    }

    boolean pending() {
        return pending;
    }
}
