package com.huanghuang.rsintegration.crafting.tree;

import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.crafting.plan.PlanResponse;
import com.huanghuang.rsintegration.crafting.plan.PlanStep;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Client-side recipe plan tree, built from a server-authoritative {@link PlanResponse}.
 * <p>
 * The server is the single source of truth: the tree is a pure view of whatever steps
 * the server resolved. Branch switching does not happen client-side — the client sends
 * the chosen recipeId back and the server re-resolves, producing a fresh {@link PlanResponse}
 * that {@link #from} rebuilds into a new tree. Collapse/camera state is reconciled by the
 * screen across rebuilds (see {@link #collectCollapsedNodes}/{@link #applyCollapsedNodes}).
 */
public final class PlanTreeModel {
    public final PlanTreeNode root;

    public PlanTreeModel(PlanTreeNode root) {
        this.root = root;
    }

    /**
     * Build a tree from a plan.
     * <p>
     * Parent/child linkage is pure I/O matching: a step is a child of another step when its
     * output is one of the parent's inputs. {@code depth} is recomputed from the root
     * ({@code parent.depth + 1}), never read from {@link PlanStep#depth()}, because the flat
     * PlanResponse assigns reuse materials (e.g. iron ingot) an ambiguous depth.
     */
    public static PlanTreeModel from(PlanResponse plan) {
        Map<IngredientKey, PlanStep> producerByOutput = new LinkedHashMap<>();
        for (PlanStep step : plan.steps()) {
            producerByOutput.put(IngredientKey.of(step.output()), step);
        }

        ItemStack target = plan.targetResult();
        PlanTreeNode root = new PlanTreeNode(
                IngredientKey.of(target), target,
                target.getCount() * Math.max(1, plan.repeatCount()), 0, null);
        PlanResponse.Availability rootAvail = plan.materials().get(target.getItem());
        if (rootAvail != null) {
            root.available = rootAvail.available();
            root.needed = rootAvail.needed();
        }

        // Path-local stack (push on enter, pop on exit) — detects genuine cycles (A→B→A)
        // without misflagging DAG reuse (iron ingot shared by two sibling components).
        Set<IngredientKey> pathStack = new LinkedHashSet<>();
        buildChildren(root, producerByOutput, pathStack, plan);
        return new PlanTreeModel(root);
    }

    /**
     * Sum every non-root node's demanded {@code amount} by item — the gross bill of materials
     * the tree shows (from-scratch demand, ignoring stock and ignoring resolver batch capping).
     * <p>
     * The server uses this to fill {@link PlanResponse#materials()} so the total-demand strip and
     * card material panel display exactly the numbers the tree renders, instead of the resolver's
     * net/capped batch counts which under- or over-report per branch.
     */
    public static Map<Item, Integer> grossDemandByItem(PlanTreeModel model) {
        Map<Item, Integer> out = new LinkedHashMap<>();
        for (PlanTreeNode child : model.root.children) accumulateDemand(child, out);
        return out;
    }

    private static void accumulateDemand(PlanTreeNode node, Map<Item, Integer> out) {
        out.merge(node.displayStack.getItem(), node.amount, Integer::sum);
        for (PlanTreeNode child : node.children) accumulateDemand(child, out);
    }

    private static void buildChildren(PlanTreeNode parent,
                                      Map<IngredientKey, PlanStep> producers,
                                      Set<IngredientKey> pathStack, PlanResponse plan) {
        PlanStep parentStep = producers.get(parent.key);
        if (parentStep == null) return; // leaf — no producing step

        // How many times the parent's recipe must run to satisfy THIS branch's demand, derived
        // from the parent node's own amount — not parentStep.batches(), which is the server's
        // GLOBAL run count for this output. When a material is reused by several parents (a DAG,
        // e.g. iron ingot feeding both an iron block and a loose stack), the global count
        // over-scales every child subtree; scaling by this branch's parent.amount keeps each
        // subtree self-consistent and correct per branch. ceilDiv: a partial batch still runs whole.
        int perBatchOutput = Math.max(1, parentStep.output().getCount());
        int parentBatches = Math.max(1, (parent.amount + perBatchOutput - 1) / perBatchOutput);
        // Merge same-item inputs (e.g. the nine gold-ingot slots of a 3×3 recipe) into one
        // entry, summing counts — the tree shows "gold ingot ×9", not nine "×1" nodes.
        // Empty grid slots carry no ingredient and are skipped.
        LinkedHashMap<IngredientKey, ItemStack> reps = new LinkedHashMap<>();
        LinkedHashMap<IngredientKey, Integer> counts = new LinkedHashMap<>();
        for (ItemStack input : parentStep.inputs()) {
            if (input.isEmpty()) continue;
            IngredientKey inputKey = IngredientKey.of(input);
            reps.putIfAbsent(inputKey, input);
            counts.merge(inputKey, input.getCount(), Integer::sum);
        }

        for (Map.Entry<IngredientKey, ItemStack> e : reps.entrySet()) {
            IngredientKey inputKey = e.getKey();
            ItemStack input = e.getValue();
            int amount = counts.get(inputKey) * parentBatches;
            PlanStep childStep = producers.get(inputKey);

            if (childStep != null) {
                if (!pathStack.add(inputKey)) {
                    // Same key already on this ancestor chain → genuine cycle.
                    PlanTreeNode cycleNode = new PlanTreeNode(inputKey, input, amount, parent.depth + 1, null)
                            .markCycle();
                    parent.children.add(cycleNode);
                    continue;
                }

                PlanTreeNode child = new PlanTreeNode(
                        inputKey, childStep.output(), amount, parent.depth + 1, childStep);
                child.limited = childStep.alternatives().size()
                        > RSIntegrationConfig.RECIPE_TREE_MAX_CANDIDATES.get();
                applyAvailability(child, plan, input);
                parent.children.add(child);

                buildChildren(child, producers, pathStack, plan);
                pathStack.remove(inputKey); // backtrack — sibling branches may reuse this material
            } else {
                PlanTreeNode leaf = new PlanTreeNode(
                        inputKey, input, amount, parent.depth + 1, null);
                applyAvailability(leaf, plan, input);
                parent.children.add(leaf);
            }
        }
    }

    private static void applyAvailability(PlanTreeNode node, PlanResponse plan, ItemStack input) {
        PlanResponse.Availability a = plan.materials().get(input.getItem());
        if (a != null) {
            node.available = a.available();
            node.needed = a.needed();
        }
    }

    /** Find a node by its item identity (first match, pre-order). Used for cost-bar centering. */
    @Nullable
    public PlanTreeNode findByKey(IngredientKey key) {
        return findByKey(root, key);
    }

    @Nullable
    private static PlanTreeNode findByKey(PlanTreeNode node, IngredientKey key) {
        if (node.key.equals(key)) return node;
        for (PlanTreeNode child : node.children) {
            PlanTreeNode hit = findByKey(child, key);
            if (hit != null) return hit;
        }
        return null;
    }

    // ── State reconciliation helpers (camera state lives on the screen) ──

    /** Snapshot the keys of every collapsed non-leaf node into {@code out}. */
    public static void collectCollapsedNodes(PlanTreeNode node, Set<IngredientKey> out) {
        if (!node.expanded && !node.isLeaf()) out.add(node.key);
        for (PlanTreeNode child : node.children) collectCollapsedNodes(child, out);
    }

    /** Re-collapse nodes whose key was collapsed before the rebuild. */
    public static void applyCollapsedNodes(PlanTreeNode node, Set<IngredientKey> collapsed) {
        if (collapsed.contains(node.key)) node.expanded = false;
        for (PlanTreeNode child : node.children) applyCollapsedNodes(child, collapsed);
    }
}
