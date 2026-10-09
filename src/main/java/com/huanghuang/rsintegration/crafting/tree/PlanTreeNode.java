package com.huanghuang.rsintegration.crafting.tree;

import com.huanghuang.rsintegration.crafting.graph.DemandRole;
import com.huanghuang.rsintegration.crafting.plan.PlanStep;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

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
    public int amount;
    public final int depth;
    @Nullable
    public final PlanStep step;    // null = leaf (raw material)
    /** Stable logical DAG identity. Repeated tree references share this id. */
    @Nullable
    public final Integer graphNodeId;
    /** How this material is used by its parent recipe. */
    public final DemandRole demandRole;
    public final List<PlanTreeNode> children = new ArrayList<>();

    /** Quantity carried by the producer edge that created this view reference. */
    public int edgeQuantity;
    /** True when this raw-material edge is sourced from the initial pool. */
    public boolean initialSource;
    /** Number of unresolved units attached to this input port. */
    public int unresolved;
    /** Output kind for producer nodes (PRIMARY/SECONDARY/REMAINDER/SYNTHETIC ordinal). */
    public int outputKindOrdinal;

    // ---- interaction state (preserved across tree rebuilds) ----
    public boolean expanded = true;
    public int batches = 1;

    // ---- availability (from PlanResponse.materials) ----
    public int available;
    public int needed;

    // ---- step prerequisites (server-authoritative) ----
    public List<Component> warnings = List.of();
    public boolean prerequisiteBlocked;
    // ---- 使用条件提示（客户端翻译，不影响服务端阻断状态） ----
    public List<Component> hints = List.of();

    // ---- structural flags ----
    public boolean cycle;
    /** True when the node has more alternative recipes than the configured candidate cap (extras hidden). */
    public boolean limited;

    /** Non-null for tag-input nodes — renders an Ingredient carousel. */
    @Nullable
    public Ingredient ingredient;

    /** Recipe-scoped key and concrete options for a player-selectable tag input. */
    @Nullable
    public String materialLockKey;
    public List<ItemStack> materialOptions = List.of();
    @Nullable
    public ItemStack lockedMaterial;

    public PlanTreeNode(IngredientKey key, ItemStack displayStack, int amount,
                        int depth, @Nullable PlanStep step) {
        this(key, displayStack, amount, depth, step, null, DemandRole.CONSUMED);
    }

    public PlanTreeNode(IngredientKey key, ItemStack displayStack, int amount,
                        int depth, @Nullable PlanStep step, DemandRole demandRole) {
        this(key, displayStack, amount, depth, step, null, demandRole);
    }

    public PlanTreeNode(IngredientKey key, ItemStack displayStack, int amount,
                        int depth, @Nullable PlanStep step, @Nullable Integer graphNodeId) {
        this(key, displayStack, amount, depth, step, graphNodeId, DemandRole.CONSUMED);
    }

    public PlanTreeNode(IngredientKey key, ItemStack displayStack, int amount,
                        int depth, @Nullable PlanStep step, @Nullable Integer graphNodeId,
                        DemandRole demandRole) {
        this.key = key;
        this.displayStack = displayStack;
        this.amount = amount;
        this.depth = depth;
        this.step = step;
        this.graphNodeId = graphNodeId;
        this.demandRole = demandRole == null ? DemandRole.CONSUMED : demandRole;
    }

    public boolean isLeaf() {
        return step == null && children.isEmpty();
    }

    /** True when this node is produced by a step that has selectable alternative recipes. */
    public boolean hasAlternatives() {
        return step != null && step.hasOrSiblings();
    }

    public PlanTreeNode markCycle() {
        this.cycle = true;
        return this;
    }
}
