package com.huanghuang.rsintegration.crafting.plan;

/** 候选配方面板的可见性与可操作性规则，保持与渲染环境无关以便单测。 */
public final class RecipeCandidateRules {
    private RecipeCandidateRules() {}

    /** 根节点永远不允许禁用；单候选节点禁用没有意义，但零候选叶子仍可切到仅库存。 */
    public static boolean canExclude(int nodeDepth, int candidateCount) {
        return nodeDepth > 0 && candidateCount != 1;
    }

    /** “仅使用库存”本质上是禁用该节点产出，根节点和单候选节点都不能选择。 */
    public static boolean canChooseStockOnly(int nodeDepth, int candidateCount) {
        return canExclude(nodeDepth, candidateCount);
    }

    /** 忘记偏好属于节点操作，只应出现在节点页，不属于诊断页。 */
    public static boolean showsForget(boolean diagnosticsTab) {
        return !diagnosticsTab;
    }

    /** “收藏全部”属于缺料诊断操作，只出现在诊断页。 */
    public static boolean showsBookmarkAll(boolean diagnosticsTab) {
        return diagnosticsTab;
    }
}
