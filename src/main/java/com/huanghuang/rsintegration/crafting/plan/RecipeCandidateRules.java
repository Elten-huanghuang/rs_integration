package com.huanghuang.rsintegration.crafting.plan;

import com.huanghuang.rsintegration.crafting.tree.PlanTreeNode;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.Map;

/** 候选配方面板的可见性与可操作性规则，保持与渲染环境无关以便单测。 */
public final class RecipeCandidateRules {
    private RecipeCandidateRules() {}

    /** 库存候选不需要伪造 PlanStep；已经选中的生产步骤始终排在前面。 */
    public static Map<ResourceLocation, String> optionsFor(PlanTreeNode node) {
        Map<ResourceLocation, String> options = new LinkedHashMap<>();
        if (node.step != null) {
            options.put(node.step.recipeId(),
                    node.step.modType() == null ? "generic" : node.step.modType().id());
            for (int i = 0; i < node.step.alternatives().size(); i++) {
                String type = i < node.step.alternativeModTypes().size()
                        ? node.step.alternativeModTypes().get(i) : "generic";
                options.putIfAbsent(node.step.alternatives().get(i), type);
            }
        }
        for (PlanGraphView.RecipeOption option : node.recipeOptions) {
            options.putIfAbsent(option.recipeId(), option.modTypeId());
        }
        return options;
    }

    public static boolean canSelect(PlanTreeNode node, @Nullable ResourceLocation recipeId) {
        return !node.containsCycle()
                && (recipeId == null || optionsFor(node).containsKey(recipeId));
    }

    /** 根节点永远不允许禁用；非根节点即使只有一个候选，也可以切到仅库存。 */
    public static boolean canExclude(int nodeDepth, int candidateCount) {
        return nodeDepth > 0;
    }

    /** “仅使用库存”本质上是禁用该节点产出，只禁止根节点选择。 */
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
