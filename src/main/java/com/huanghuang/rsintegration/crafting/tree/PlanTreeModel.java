package com.huanghuang.rsintegration.crafting.tree;

import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.crafting.plan.PlanGraphView;
import com.huanghuang.rsintegration.crafting.plan.PlanResponse;
import com.huanghuang.rsintegration.crafting.plan.PlanStep;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.HashSet;
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
        if (plan.graph() != null && !plan.graph().nodes().isEmpty()) {
            return fromGraph(plan);
        }
        return fromLegacySteps(plan);
    }

    private static int maxTreeCandidates() {
        try {
            return RSIntegrationConfig.RECIPE_TREE_MAX_CANDIDATES.get();
        } catch (IllegalStateException ignored) {
            return 8;
        }
    }

    private static PlanTreeModel fromLegacySteps(PlanResponse plan) {
        Map<IngredientKey, PlanStep> producerByOutput = new LinkedHashMap<>();
        for (PlanStep step : plan.steps()) {
            producerByOutput.putIfAbsent(IngredientKey.of(step.output()), step);
        }

        ItemStack target = plan.targetResult();
        PlanTreeNode root = new PlanTreeNode(
                IngredientKey.of(target), target,
                target.getCount() * Math.max(1, plan.repeatCount()), 0, null);
        PlanResponse.Availability rootAvail = plan.availability(target);
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

    private static PlanTreeModel fromGraph(PlanResponse plan) {
        PlanGraphView graph = plan.graph();
        Map<Integer, PlanGraphView.NodeView> nodes = new HashMap<>();
        for (PlanGraphView.NodeView node : graph.nodes()) nodes.put(node.nodeId(), node);

        ItemStack target = plan.targetResult();
        PlanStep targetStep = null;
        if (plan.recipeId() != null) {
            for (PlanStep step : plan.steps()) {
                if (plan.recipeId().equals(step.recipeId().toString())) {
                    targetStep = step;
                    break;
                }
            }
        }
        PlanTreeNode root = new PlanTreeNode(IngredientKey.of(target), target,
                target.getCount() * Math.max(1, plan.repeatCount()), 0, targetStep);
        if (targetStep != null) {
            root.limited = targetStep.alternatives().size()
                    > RSIntegrationConfig.RECIPE_TREE_MAX_CANDIDATES.get();
        }
        applyAvailability(root, plan, target);

        // Root allocations are explicit sinks. A plan may contain several root
        // demands; each allocation becomes one view reference under the visual root.
        Set<Integer> path = new HashSet<>();
        for (PlanGraphView.RootView demand : graph.roots()) {
            for (PlanGraphView.RootEdgeView edge : demand.allocations()) {
                root.children.add(buildGraphReference(edge.source(), edge.material(), edge.quantity(),
                        1, graph, nodes, path, plan));
            }
            if (demand.unresolvedQuantity() > 0) {
                PlanTreeNode missing = new PlanTreeNode(IngredientKey.of(demand.display()),
                        demand.display(), demand.unresolvedQuantity(), 1, null);
                missing.unresolved = demand.unresolvedQuantity();
                applyAvailability(missing, plan, demand.display());
                root.children.add(missing);
            }
        }
        return new PlanTreeModel(root);
    }

    private static PlanTreeNode buildGraphReference(PlanGraphView.SourceView source,
                                                     ItemStack material, int quantity, int depth,
                                                     PlanGraphView graph,
                                                     Map<Integer, PlanGraphView.NodeView> nodes,
                                                     Set<Integer> path,
                                                     PlanResponse plan) {
        if (source.initial()) {
            PlanTreeNode leaf = new PlanTreeNode(IngredientKey.of(material), material,
                    quantity, depth, null);
            leaf.edgeQuantity = quantity;
            leaf.initialSource = true;
            applyAvailability(leaf, plan, material);
            return leaf;
        }

        PlanGraphView.NodeView producer = nodes.get(source.producerNodeId());
        if (producer == null) {
            PlanTreeNode broken = new PlanTreeNode(IngredientKey.of(material), material,
                    quantity, depth, null).markCycle();
            broken.edgeQuantity = quantity;
            return broken;
        }
        if (!path.add(producer.nodeId())) {
            PlanTreeNode cycle = new PlanTreeNode(IngredientKey.of(material), material,
                    quantity, depth, producer.asPlanStep(), producer.nodeId()).markCycle();
            cycle.edgeQuantity = quantity;
            return cycle;
        }

        // The edge material is the exact output port selected by the server. It may be a
        // secondary output or crafting remainder, so using primaryOutput here would show
        // the wrong item even though the logical producer node is the same.
        ItemStack display = material.isEmpty()
                ? producer.primaryOutput().copyWithCount(1) : material.copyWithCount(1);
        PlanTreeNode node = new PlanTreeNode(IngredientKey.of(display), display,
                quantity, depth, producer.asPlanStep(), producer.nodeId());
        node.limited = node.step.alternatives().size() > maxTreeCandidates();
        node.edgeQuantity = quantity;
        for (PlanGraphView.OutputView output : producer.outputs()) {
            if (output.portIndex() == source.producerPortIndex()) {
                node.outputKindOrdinal = output.kindOrdinal();
                break;
            }
        }
        applyAvailability(node, plan, display);

        for (PlanGraphView.EdgeView edge : graph.edges()) {
            if (edge.consumerNodeId() != producer.nodeId()) continue;
            PlanTreeNode child = buildGraphReference(edge.source(), edge.material(), edge.quantity(),
                    depth + 1, graph, nodes, path, plan);
            node.children.add(child);
        }
        // Unresolved demand is a separate portion of the input port. Keep it as
        // its own view reference even when the same port is partially supplied;
        // attaching it to an allocated child hides the conservation split.
        for (PlanGraphView.UnresolvedView view : graph.unresolved()) {
            if (view.consumerNodeId() != producer.nodeId()) continue;
            PlanTreeNode missing = new PlanTreeNode(IngredientKey.of(view.display()), view.display(),
                    view.quantity(), depth + 1, null);
            missing.unresolved = view.quantity();
            applyAvailability(missing, plan, view.display());
            node.children.add(missing);
        }
        path.remove(producer.nodeId());
        return node;
    }

    /**
     * Count visual references per logical graph node. A value >1 means the
     * producer is shared by multiple consumer edges even though each tree branch
     * owns a separate view node.
     */
    public Map<Integer, Integer> graphReferenceCounts() {
        Map<Integer, Integer> counts = new LinkedHashMap<>();
        collectGraphReferences(root, counts);
        return Map.copyOf(counts);
    }

    private static void collectGraphReferences(PlanTreeNode node, Map<Integer, Integer> counts) {
        if (node.graphNodeId != null) counts.merge(node.graphNodeId, 1, Integer::sum);
        for (PlanTreeNode child : node.children) collectGraphReferences(child, counts);
    }

    /**
     * Sum every non-root node's demanded {@code amount} by item — the gross bill of materials
     * the tree shows (from-scratch demand, ignoring stock and ignoring resolver batch capping).
     * <p>
     * The server uses this to fill {@link PlanResponse#materials()} so the total-demand strip and
     * card material panel display exactly the numbers the tree renders, instead of the resolver's
     * net/capped batch counts which under- or over-report per branch.
     */
    public static Map<IngredientKey, Integer> grossDemandByKey(PlanTreeModel model) {
        Map<IngredientKey, Integer> out = new LinkedHashMap<>();
        for (PlanTreeNode child : model.root.children) accumulateDemand(child, out);
        return out;
    }

    private static void accumulateDemand(PlanTreeNode node, Map<IngredientKey, Integer> out) {
        out.merge(node.key, node.amount, Integer::sum);
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
        PlanResponse.Availability a = plan.availability(input);
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

    /** Stable collapse identity: DAG nodes use NodeId; legacy/raw nodes fall back to item identity. */
    public record CollapseKey(@Nullable Integer graphNodeId, IngredientKey ingredientKey) {
        private static CollapseKey of(PlanTreeNode node) {
            return new CollapseKey(node.graphNodeId, node.key);
        }
    }

    /** Snapshot the identities of every collapsed non-leaf node into {@code out}. */
    public static void collectCollapsedNodes(PlanTreeNode node, Set<CollapseKey> out) {
        if (!node.expanded && !node.isLeaf()) out.add(CollapseKey.of(node));
        for (PlanTreeNode child : node.children) collectCollapsedNodes(child, out);
    }

    /** Re-collapse nodes whose stable identity was collapsed before the rebuild. */
    public static void applyCollapsedNodes(PlanTreeNode node, Set<CollapseKey> collapsed) {
        if (collapsed.contains(CollapseKey.of(node))) node.expanded = false;
        for (PlanTreeNode child : node.children) applyCollapsedNodes(child, collapsed);
    }
}
