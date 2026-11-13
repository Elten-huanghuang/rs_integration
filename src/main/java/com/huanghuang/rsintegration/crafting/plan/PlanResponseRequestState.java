package com.huanghuang.rsintegration.crafting.plan;

import java.util.Objects;

/** 关联最新预览及延迟刷新，并让提前确认等待对应计划完成。 */
final class PlanResponseRequestState {
    private long activeRequestId;
    private String expectedRecipeId;
    private boolean pending;
    private boolean refreshScheduled;
    private boolean retryRequired;
    private boolean confirmationRequested;

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
        confirmationRequested = false;
        return supersededRequestId;
    }

    void scheduleRefresh() {
        refreshScheduled = true;
        confirmationRequested = false;
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
        retryRequired = true;
        confirmationRequested = false;
        return true;
    }

    boolean requestConfirmation() {
        confirmationRequested = waitingForPreview();
        return confirmationRequested;
    }

    boolean confirmationRequested() {
        return confirmationRequested;
    }

    boolean consumeConfirmation(boolean executable) {
        if (waitingForPreview()) return false;
        boolean execute = confirmationRequested && executable;
        confirmationRequested = false;
        return execute;
    }

    void cancelConfirmation() {
        confirmationRequested = false;
    }

    boolean accept(long requestId, String recipeId) {
        // 配方相同也可能是独立任务；只有本页面发起的请求才能更新页面。
        if (requestId != activeRequestId) return false;
        // 错误响应可能没有配方 ID，由页面显示错误并保留上一份方案。
        if (recipeId != null && !recipeId.isEmpty()
                && !Objects.equals(recipeId, expectedRecipeId)) return false;
        activeRequestId = requestId;
        pending = false;
        retryRequired = false;
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
