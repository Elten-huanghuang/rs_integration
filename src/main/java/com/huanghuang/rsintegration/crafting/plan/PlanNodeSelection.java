package com.huanghuang.rsintegration.crafting.plan;

import com.huanghuang.rsintegration.crafting.tree.IngredientKey;
import com.huanghuang.rsintegration.crafting.tree.PlanTreeNode;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** 重算后按材料需求恢复查看位置；不把材料锁误当成全树物品锁。 */
public record PlanNodeSelection(IngredientKey key, @Nullable String materialLockKey,
                                List<Integer> path) {
    public PlanNodeSelection {
        path = List.copyOf(path);
    }

    public static PlanNodeSelection capture(PlanTreeNode root, PlanTreeNode selected) {
        List<Integer> path = new ArrayList<>();
        findPath(root, selected, path);
        return new PlanNodeSelection(selected.key, selected.materialLockKey, path);
    }

    public PlanNodeSelection withMaterial(IngredientKey material) {
        return new PlanNodeSelection(material, materialLockKey, path);
    }

    public PlanTreeNode resolve(PlanTreeNode root) {
        PlanTreeNode atPath = root;
        for (int index : path) {
            if (index >= atPath.children.size()) { atPath = null; break; }
            atPath = atPath.children.get(index);
        }
        if (matches(atPath, true)) return atPath;
        PlanTreeNode match = find(root, true);
        if (match != null) return match;
        // 自动选材可能换成另一种物品，仍跟随同一配方中的同一材料需求。
        if (materialLockKey != null) {
            if (matches(atPath, false)) return atPath;
            match = find(root, false);
            if (match != null) return match;
        }
        return root;
    }

    private boolean matches(@Nullable PlanTreeNode node, boolean requireKey) {
        return node != null && Objects.equals(materialLockKey, node.materialLockKey)
                && (!requireKey || key.equals(node.key));
    }

    @Nullable
    private PlanTreeNode find(PlanTreeNode node, boolean requireKey) {
        if (matches(node, requireKey)) return node;
        for (PlanTreeNode child : node.children) {
            PlanTreeNode found = find(child, requireKey);
            if (found != null) return found;
        }
        return null;
    }

    private static boolean findPath(PlanTreeNode node, PlanTreeNode target, List<Integer> path) {
        if (node == target) return true;
        for (int index = 0; index < node.children.size(); index++) {
            path.add(index);
            if (findPath(node.children.get(index), target, path)) return true;
            path.remove(path.size() - 1);
        }
        return false;
    }
}
