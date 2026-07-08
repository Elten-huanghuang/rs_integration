package com.huanghuang.rsintegration.crafting.tree;

import com.huanghuang.rsintegration.crafting.plan.PlanStep;
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
    public final int amount;
    public final int depth;
    @Nullable
    public final PlanStep step;    // null = leaf (raw material)
    public final List<PlanTreeNode> children = new ArrayList<>();

    // ---- interaction state (preserved across tree rebuilds) ----
    public boolean expanded = true;
    public int batches = 1;

    // ---- availability (from PlanResponse.materials) ----
    public int available;
    public int needed;

    // ---- structural flags ----
    public boolean cycle;
    /** True when the node has more alternative recipes than the configured candidate cap (extras hidden). */
    public boolean limited;

    /** Non-null for tag-input nodes — renders an Ingredient carousel. */
    @Nullable
    public Ingredient ingredient;

    public PlanTreeNode(IngredientKey key, ItemStack displayStack, int amount,
                        int depth, @Nullable PlanStep step) {
        this.key = key;
        this.displayStack = displayStack;
        this.amount = amount;
        this.depth = depth;
        this.step = step;
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
