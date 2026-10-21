package com.huanghuang.rsintegration.crafting.plan;

/** 客户端是否用新预览替换当前计划树的纯判定。 */
public final class PlanPreviewDecision {
    private PlanPreviewDecision() {}

    /**
     * 失败预览若连目标配方步骤都没有，替换后节点会消失，玩家将无法撤回选择。
     * 这类响应只提示错误，不应破坏仍可操作的计划树。
     */
    public static boolean keepCurrentPlan(PlanResponse current, PlanResponse incoming) {
        return keepCurrentPlan(current, incoming, false);
    }

    /**
     * 配方切换是一次可撤销编辑：预览失败时保留旧树，玩家才能从同一候选面板改回。
     * 其他刷新请求仍按原逻辑展示缺料计划。
     */
    public static boolean keepCurrentPlan(PlanResponse current, PlanResponse incoming,
                                          boolean recipeSwitch) {
        if (current == null || current.steps().isEmpty()) return false;
        if (incoming == null || incoming.success()
                || incoming.recipeId() == null || incoming.recipeId().isEmpty()) {
            return false;
        }
        // 循环是规划器证明出的服务端终态。不要再从展示用 PlanStep 反推
        // 材料图：那里混合了展示代表物、可选配方和合法的自增配方。
        if (recipeSwitch && incoming.outcome() == PlanOutcome.CYCLE) return true;
        return incoming.steps().stream().noneMatch(step ->
                step.recipeId().toString().equals(incoming.recipeId()));
    }
}
