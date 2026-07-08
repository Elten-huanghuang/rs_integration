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
 * The tree view writes here (user picks an alternative); the card view reads
 * {@link #deriveActiveRecipeIds} to render only the chosen path. No per-switch
 * network traffic — selections are submitted once, with {@code selectedBranches},
 * when the player presses Start.
 * <p>
 * Keyed by {@link IngredientKey} (item + NBT, never count), so a selection made
 * on one "oak planks" node applies to every same-key node, and selections survive
 * tree rebuilds ({@link #bindTree}).
 */
public final class SelectedPath {

    /** A chosen alternative: which dropdown row ({@code index}) and its recipe. */
    public record Selection(int index, ResourceLocation recipeId) {}

    // Only nodes with alternatives (hasOrSiblings) appear here.
    private final Map<IngredientKey, Selection> branchSelections = new LinkedHashMap<>();

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
        recalculatePending();
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
        if (prev == null || prev.index() != alternativeIndex) {
            dirty = true;
        }
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
        for (Map.Entry<IngredientKey, Selection> e : branchSelections.entrySet()) {
            out.put(e.getKey(), e.getValue().recipeId());
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
