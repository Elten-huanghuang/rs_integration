package com.huanghuang.rsintegration.crafting.tree;

import com.huanghuang.rsintegration.crafting.graph.DemandRole;
import com.huanghuang.rsintegration.crafting.plan.ParallelPlan;
import com.huanghuang.rsintegration.crafting.plan.PlanGraphView;
import com.huanghuang.rsintegration.crafting.plan.PlanStep;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A single node in the recipe plan tree.
 * <p>
 * {@code depth} is always recomputed from root during tree construction
 * ({@code parent.depth + 1}) — never read from {@code PlanStep.depth()}.
 * <p>
 * Identity: two nodes are "the same" across tree rebuilds when their
 * {@link IngredientKey} equals. This is used by state reconciliation.
 */
public final class PlanTreeNode {
    public final IngredientKey key;
    public final ItemStack displayStack;
    /** 当前分支的材料总需求，包含已有库存可满足的部分。 */
    public long amount;
    /** 配方节点计划实际产出的数量，用于和材料总需求区分。 */
    public long productionAmount;
    public int depth;
    @Nullable
    public PlanStep step;    // null = leaf (raw material)
    /** 计划阶段估算的并行分派；旧计划/无机器节点为 null。 */
    @Nullable
    public ParallelPlan parallelPlan;
    /** Stable logical DAG identity. Repeated tree references share this id. */
    @Nullable
    public final Integer graphNodeId;
    /** 合并展示所包含的真实生产节点，用于批次数去重和引用标记。 */
    public final Set<Integer> producerNodeIds = new LinkedHashSet<>();
    /** How this material is used by its parent recipe. */
    public final DemandRole demandRole;
    public final List<PlanTreeNode> children = new ArrayList<>();

    /** Quantity carried by the producer edge that created this view reference. */
    public long edgeQuantity;
    /** True when this raw-material edge is sourced from the initial pool. */
    public boolean initialSource;
    /** Number of unresolved units attached to this input port. */
    public long unresolved;
    /** Output kind for producer nodes (PRIMARY/SECONDARY/REMAINDER/SYNTHETIC ordinal). */
    public int outputKindOrdinal;

    // ---- interaction state (preserved across tree rebuilds) ----
    public boolean expanded = true;

    // ---- availability (from PlanResponse.materials) ----
    public long available;
    public long needed;

    // ---- step prerequisites (server-authoritative) ----
    public List<Component> warnings = List.of();
    public boolean prerequisiteBlocked;
    // ---- 使用条件提示（客户端翻译，不影响服务端阻断状态） ----
    public List<Component> hints = List.of();

    // ---- structural flags ----
    public boolean cycle;
    /** True when the node has more alternative recipes than the configured candidate cap (extras hidden). */
    public boolean limited;
    /** True when child nodes are hidden by the client tree depth/node display limits. */
    public boolean viewTruncated;
    /**
     * 引用节点：同一批产出在树里只在首个位置展开成本，其余出现位置仅表示消耗关系。
     * 这些节点带 step 但没有子节点，界面上与展开节点完全一样的图标/数量会让人误以为要生产两批，
     * 因此单独标记出来画引用角标。
     */
    public boolean referenceOnly;

    /** Non-null for tag-input nodes — renders an Ingredient carousel. */
    @Nullable
    public Ingredient ingredient;

    /** Recipe-scoped key and concrete options for a player-selectable tag input. */
    @Nullable
    public String materialLockKey;
    public List<ItemStack> materialOptions = List.of();
    /** 独立的候选元数据，不代表该库存材料需要额外生产。 */
    public List<PlanGraphView.RecipeOption> recipeOptions = List.of();
    @Nullable
    public ItemStack lockedMaterial;

    public PlanTreeNode(IngredientKey key, ItemStack displayStack, long amount,
                        int depth, @Nullable PlanStep step) {
        this(key, displayStack, amount, depth, step, null, DemandRole.CONSUMED);
    }

    public PlanTreeNode(IngredientKey key, ItemStack displayStack, long amount,
                        int depth, @Nullable PlanStep step, DemandRole demandRole) {
        this(key, displayStack, amount, depth, step, null, demandRole);
    }

    public PlanTreeNode(IngredientKey key, ItemStack displayStack, long amount,
                        int depth, @Nullable PlanStep step, @Nullable Integer graphNodeId) {
        this(key, displayStack, amount, depth, step, graphNodeId, DemandRole.CONSUMED);
    }

    public PlanTreeNode(IngredientKey key, ItemStack displayStack, long amount,
                        int depth, @Nullable PlanStep step, @Nullable Integer graphNodeId,
                        DemandRole demandRole) {
        this.key = key;
        this.displayStack = displayStack;
        this.amount = amount;
        this.depth = depth;
        this.step = step;
        this.parallelPlan = step == null ? null : step.parallelPlan();
        this.graphNodeId = graphNodeId;
        if (graphNodeId != null) producerNodeIds.add(graphNodeId);
        this.demandRole = demandRole == null ? DemandRole.CONSUMED : demandRole;
    }

    public boolean isLeaf() {
        return step == null && children.isEmpty();
    }

    /** 配方节点显示补做产量，原料叶节点仍显示材料需求。 */
    public long displayAmount() {
        return step != null && productionAmount > 0L ? productionAmount : amount;
    }

    /** True when this node is produced by a step that has selectable alternative recipes. */
    public boolean hasAlternatives() {
        // 循环引用只用于揭示计划中的回边，不允许继续从这里改写分支，
        // 否则 9x -> 8x -> 9x 一类路径会被再次提交给服务端。
        return step != null && !containsCycle() && step.hasOrSiblings();
    }

    /** 当前节点或其子树是否包含循环回边。 */
    public boolean containsCycle() {
        if (cycle) return true;
        for (PlanTreeNode child : children) {
            if (child.containsCycle()) return true;
        }
        return false;
    }

    public PlanTreeNode markCycle() {
        this.cycle = true;
        return this;
    }
}
