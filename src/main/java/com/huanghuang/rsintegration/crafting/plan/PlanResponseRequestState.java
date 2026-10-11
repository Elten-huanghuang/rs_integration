package com.huanghuang.rsintegration.crafting.plan;

import java.util.Objects;

/** 用最新预览请求关联响应，根配方切换时允许更新当前页面的配方 ID。 */
final class PlanResponseRequestState {
    private long activeRequestId;
    private String expectedRecipeId;
    private boolean pending;

    PlanResponseRequestState(long requestId, String recipeId) {
        activeRequestId = requestId;
        expectedRecipeId = recipeId;
    }

    void beginPreview(long requestId, String recipeId) {
        activeRequestId = requestId;
        expectedRecipeId = recipeId;
        pending = true;
    }

    boolean accept(long requestId, String recipeId) {
        if (requestId == 0L ? activeRequestId != 0L : requestId < activeRequestId) {
            return false;
        }
        if (pending && requestId != activeRequestId) return false;
        // 错误响应可能没有配方 ID，由页面显示错误并保留上一份方案。
        if (recipeId != null && !recipeId.isEmpty()
                && !Objects.equals(recipeId, expectedRecipeId)) return false;
        activeRequestId = requestId;
        pending = false;
        return true;
    }

    long activeRequestId() {
        return activeRequestId;
    }

    String expectedRecipeId() {
        return expectedRecipeId;
    }
}
