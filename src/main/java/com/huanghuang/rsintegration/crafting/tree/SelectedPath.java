package com.huanghuang.rsintegration.crafting.tree;

import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Client-side branch-selection state for the recipe tree (v3.4).
 * <p>
 * The tree view writes a draft here when the player picks an alternative. A
 * successful server preview confirms that draft; a failed preview restores the
 * previous branch snapshot. Selections are material-scoped so every occurrence
 * of the same item uses one consistent producer in the shared recipe graph.
 * <p>
 * Keyed by {@link IngredientKey} (item + NBT, never count), so a selection made
 * on one "oak planks" node applies to every same-key node, and selections survive
 * tree rebuilds ({@link #bindTree}).
 */
public final class SelectedPath {

    /** A chosen alternative: which dropdown row ({@code index}) and its recipe. */
    public record Selection(int index, ResourceLocation recipeId) {}

    /** 编辑前的完整状态，用于失败预览回滚，不把一次不可执行尝试变成持久偏好。 */
    public record BranchSnapshot(@Nullable Selection selection, boolean explicit) {}

    // Only nodes with alternatives (hasOrSiblings) appear here.
    private final Map<IngredientKey, Selection> branchSelections = new LinkedHashMap<>();
    private final Set<IngredientKey> explicitSelections = new LinkedHashSet<>();

    @Nullable
    private PlanTreeModel tree;
    private int pendingBranches;
    private boolean dirty;

    /**
     * Bind (or rebind) the tree this path describes. Called whenever the model is
     * built or rebuilt. Existing selections persist by key — this is the state
     * reconciliation that stops branch choices from resetting on refresh (4.3).
     */
    public void bindTree(PlanTreeModel model) {
        this.tree = model;
        Set<IngredientKey> currentKeys = new LinkedHashSet<>();
        collectTreeKeys(model.root, currentKeys);
        branchSelections.keySet().retainAll(currentKeys);
        explicitSelections.retainAll(currentKeys);
        recalculatePending();
    }

    private void collectTreeKeys(PlanTreeNode node, Set<IngredientKey> keys) {
        keys.add(node.key);
        for (PlanTreeNode child : node.children) collectTreeKeys(child, keys);
    }

    /**
     * Auto-select every alternative node to its current (server-chosen) recipe.
     * Does not mark dirty — this is the default path, not a user edit (2.5 opt.1).
     */
    public void initDefaults() {
        if (tree == null) return;
        selectDefaults(tree.root);
        recalculatePending();
    }

    private void selectDefaults(PlanTreeNode node) {
        if (node.hasAlternatives() && !branchSelections.containsKey(node.key)) {
            branchSelections.put(node.key, new Selection(0, node.step.recipeId()));
        }
        for (PlanTreeNode child : node.children) selectDefaults(child);
    }

    /** User picked an alternative. Marks dirty when it differs from the prior choice. */
    public void selectBranch(IngredientKey nodeKey, int alternativeIndex, ResourceLocation recipeId) {
        Selection prev = branchSelections.put(nodeKey, new Selection(alternativeIndex, recipeId));
        explicitSelections.add(nodeKey);
        if (prev == null || prev.index() != alternativeIndex || !prev.recipeId().equals(recipeId)) {
            dirty = true;
        }
        recalculatePending();
    }

    public BranchSnapshot snapshotBranch(IngredientKey nodeKey) {
        return new BranchSnapshot(branchSelections.get(nodeKey),
                explicitSelections.contains(nodeKey));
    }

    public void restoreBranch(IngredientKey nodeKey, BranchSnapshot snapshot) {
        if (snapshot.selection() == null) branchSelections.remove(nodeKey);
        else branchSelections.put(nodeKey, snapshot.selection());
        if (snapshot.explicit()) explicitSelections.add(nodeKey);
        else explicitSelections.remove(nodeKey);
        dirty = true;
        recalculatePending();
    }

    public void clearBranch(IngredientKey nodeKey) {
        branchSelections.remove(nodeKey);
        explicitSelections.remove(nodeKey);
        dirty = true;
        recalculatePending();
    }

    public boolean isDirty() {
        return dirty;
    }

    public boolean isSelected(IngredientKey key) {
        return branchSelections.containsKey(key);
    }

    public int selectedIndex(IngredientKey key) {
        Selection sel = branchSelections.get(key);
        return sel == null ? -1 : sel.index();
    }

    @Nullable
    public ResourceLocation selectedRecipe(IngredientKey key) {
        Selection sel = branchSelections.get(key);
        return sel == null ? null : sel.recipeId();
    }

    public int pendingBranches() {
        return pendingBranches;
    }

    public boolean allConfirmed() {
        return pendingBranches == 0;
    }

    /** Chosen recipe per node key, for the {@code selectedBranches} confirm packet (Phase 4). */
    public Map<IngredientKey, ResourceLocation> exportSelections() {
        Map<IngredientKey, ResourceLocation> out = new LinkedHashMap<>();
        for (IngredientKey key : explicitSelections) {
            Selection selection = branchSelections.get(key);
            if (selection != null) out.put(key, selection.recipeId());
        }
        return Collections.unmodifiableMap(out);
    }

    /**
     * Recipe ids on the confirmed green path (every ancestor also confirmed).
     * The card view renders only these steps.
     */
    public Set<ResourceLocation> deriveActiveRecipeIds(PlanTreeModel model) {
        Set<ResourceLocation> active = new LinkedHashSet<>();
        collectActive(model.root, true, active);
        return active;
    }

    private void collectActive(PlanTreeNode node, boolean ancestorConfirmed, Set<ResourceLocation> out) {
        boolean thisConfirmed = ancestorConfirmed
                && (!node.hasAlternatives() || branchSelections.containsKey(node.key));
        if (thisConfirmed && node.step != null) {
            out.add(node.step.recipeId());
        }
        for (PlanTreeNode child : node.children) {
            collectActive(child, thisConfirmed, out);
        }
    }

    /** Count distinct alternative keys still unselected. Same-key nodes share one entry. */
    private void recalculatePending() {
        if (tree == null) {
            pendingBranches = 0;
            return;
        }
        Set<IngredientKey> unresolved = new LinkedHashSet<>();
        collectUnresolved(tree.root, unresolved);
        pendingBranches = unresolved.size();
    }

    private void collectUnresolved(PlanTreeNode node, Set<IngredientKey> out) {
        if (node.hasAlternatives() && !branchSelections.containsKey(node.key)) {
            out.add(node.key);
        }
        for (PlanTreeNode child : node.children) collectUnresolved(child, out);
    }
}
