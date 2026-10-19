package com.huanghuang.rsintegration.crafting.tree;

import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.crafting.graph.DemandRole;
import com.huanghuang.rsintegration.crafting.plan.PlanGraphView;
import com.huanghuang.rsintegration.crafting.plan.PlanResponse;
import com.huanghuang.rsintegration.crafting.plan.PlanStep;
import com.huanghuang.rsintegration.crafting.quantity.SaturatingLongMath;
import com.huanghuang.rsintegration.mods.embers.EmbersMachineHints;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import java.util.ArrayList;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
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
    private final boolean truncated;

    public PlanTreeModel(PlanTreeNode root) {
        this(root, false);
    }

    private PlanTreeModel(PlanTreeNode root, boolean truncated) {
        this.root = root;
        this.truncated = truncated;
    }

    /** True when the configured client display limits hid part of the execution tree. */
    public boolean truncated() {
        return truncated;
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
        return from(plan, treeLimits());
    }

    static PlanTreeModel from(PlanResponse plan, int maxDepth, int maxNodes) {
        return from(plan, new TreeLimits(maxDepth, maxNodes));
    }

    private static PlanTreeModel from(PlanResponse plan, TreeLimits limits) {
        BuildBudget budget = new BuildBudget(limits);
        if (plan.graph() != null
                && (!plan.graph().nodes().isEmpty() || !plan.graph().roots().isEmpty())) {
            return fromGraph(plan, budget);
        }
        return fromLegacySteps(plan, budget);
    }

    private static int maxTreeCandidates() {
        try {
            return RSIntegrationConfig.RECIPE_TREE_MAX_CANDIDATES.get();
        } catch (IllegalStateException ignored) {
            return 8;
        }
    }

    private static TreeLimits treeLimits() {
        try {
            return new TreeLimits(
                    RSIntegrationConfig.RECIPE_TREE_MAX_DEPTH.get(),
                    RSIntegrationConfig.RECIPE_TREE_MAX_NODES.get());
        } catch (IllegalStateException ignored) {
            return new TreeLimits(16, 512);
        }
    }

    private record TreeLimits(int maxDepth, int maxNodes) {}

    private static final class BuildBudget {
        private final int maxDepth;
        private final int maxNodes;
        private int createdNodes = 1;
        private boolean truncated;

        private BuildBudget(TreeLimits limits) {
            this.maxDepth = Math.max(0, limits.maxDepth());
            this.maxNodes = Math.max(1, limits.maxNodes());
        }

        private boolean tryClaimNode(int depth) {
            if (depth > maxDepth || createdNodes >= maxNodes) {
                truncated = true;
                return false;
            }
            createdNodes++;
            return true;
        }

        private boolean truncated() {
            return truncated;
        }
    }

    private static PlanTreeModel fromLegacySteps(PlanResponse plan, BuildBudget budget) {
        Map<IngredientKey, PlanStep> producerByOutput = new LinkedHashMap<>();
        for (PlanStep step : plan.steps()) {
            producerByOutput.putIfAbsent(IngredientKey.of(step.output()), step);
        }

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
        PlanTreeNode root = new PlanTreeNode(
                IngredientKey.of(target), target,
                target.getCount() * Math.max(1, plan.repeatCount()), 0, targetStep);
        if (targetStep != null) {
            root.limited = targetStep.alternatives().size() > maxTreeCandidates();
        }
        applyAvailability(root, plan, target);

        // Path-local stack (push on enter, pop on exit) — detects genuine cycles (A→B→A)
        // without misflagging DAG reuse (iron ingot shared by two sibling components).
        Set<IngredientKey> pathStack = new LinkedHashSet<>();
        Set<PlanStep> expandedSteps = new HashSet<>();
        buildChildren(root, producerByOutput, pathStack, expandedSteps, plan, budget);
        retainUnlinkedLegacySteps(root, plan, producerByOutput, expandedSteps, budget);
        return new PlanTreeModel(root, budget.truncated());
    }

    /**
     * A legacy response is a flat execution manifest. Item/NBT display
     * representatives can occasionally prevent an otherwise valid step from
     * linking through {@link IngredientKey}; card view still shows that step,
     * while tree view used to discard it. Preserve the manifest invariant by
     * attaching any unrendered execution step to the root, matching the graph
     * response fallback below.
     */
    private static void retainUnlinkedLegacySteps(
            PlanTreeNode root, PlanResponse plan,
            Map<IngredientKey, PlanStep> producers, Set<PlanStep> expandedSteps,
            BuildBudget budget) {
        Set<ResourceLocation> rendered = new HashSet<>();
        collectRenderedRecipeIds(root, rendered);
        ResourceLocation targetRecipe = plan.recipeId() == null
                ? null : ResourceLocation.tryParse(plan.recipeId());
        Set<Item> reachableOutputs = legacyReachableOutputs(root.step, plan.steps());
        for (PlanStep step : plan.steps()) {
            if (step.recipeId().equals(targetRecipe) || rendered.contains(step.recipeId())
                    || !reachableOutputs.contains(step.output().getItem())) continue;
            ItemStack output = step.output().copyWithCount(1);
            if (output.isEmpty()) continue;
            if (!budget.tryClaimNode(1)) {
                root.viewTruncated = true;
                break;
            }
            long amount = Math.max(1L, step.totalOutputCount());
            PlanTreeNode unlinked = new PlanTreeNode(IngredientKey.of(output), output,
                    amount, 1, step);
            unlinked.limited = step.alternatives().size() > maxTreeCandidates();
            applyAvailability(unlinked, plan, output);
            Set<IngredientKey> path = new LinkedHashSet<>();
            path.add(unlinked.key);
            buildChildren(unlinked, producers, path, expandedSteps, plan, budget);
            root.children.add(unlinked);
            collectRenderedRecipeIds(unlinked, rendered);
        }
    }

    private static Set<Item> legacyReachableOutputs(
            @Nullable PlanStep target, List<PlanStep> steps) {
        if (target == null) return Set.of();
        Set<Item> reachable = new HashSet<>();
        for (ItemStack input : target.inputs()) {
            if (!input.isEmpty()) reachable.add(input.getItem());
        }
        boolean changed = true;
        while (changed) {
            changed = false;
            for (PlanStep step : steps) {
                if (!reachable.contains(step.output().getItem())) continue;
                for (ItemStack input : step.inputs()) {
                    if (!input.isEmpty() && reachable.add(input.getItem())) changed = true;
                }
            }
        }
        return reachable;
    }

    private static PlanTreeModel fromGraph(PlanResponse plan, BuildBudget budget) {
        PlanGraphView graph = plan.graph();
        GraphViewIndex index = GraphViewIndex.from(graph);

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
                    > maxTreeCandidates();
        }
        applyAvailability(root, plan, target);

        // Root allocations are explicit sinks. Merge repeated input slots backed by
        // the same producer output so a recipe that consumes four units from one
        // batch is rendered as one "x4" producer reference, not four "x1" nodes.
        Set<Integer> path = new HashSet<>();
        Set<Integer> expandedProducers = new HashSet<>();
        List<PlanGraphView.RootEdgeView> rootEdges = new ArrayList<>();
        Map<IngredientKey, UnresolvedReference> unresolvedRoots = new LinkedHashMap<>();
        for (PlanGraphView.RootView demand : graph.roots()) {
            rootEdges.addAll(demand.allocations());
            if (demand.unresolvedQuantity() > 0) {
                IngredientKey key = IngredientKey.of(demand.display());
                unresolvedRoots.compute(key, (ignored, existing) -> existing == null
                        ? new UnresolvedReference(demand.display(), demand.unresolvedQuantity(),
                                DemandRole.CONSUMED)
                        : existing.add(demand.unresolvedQuantity()));
            }
        }
        for (GraphReference edge : mergeRootEdges(rootEdges)) {
            PlanTreeNode child = buildGraphReference(edge.source(), edge.material(), edge.quantity(),
                    edge.role(), 1, index, path, expandedProducers, plan, budget);
            if (child == null) {
                root.viewTruncated = true;
                break;
            }
            root.children.add(child);
        }
        for (UnresolvedReference missingRef : unresolvedRoots.values()) {
            if (!budget.tryClaimNode(1)) {
                root.viewTruncated = true;
                break;
            }
            PlanTreeNode missing = new PlanTreeNode(IngredientKey.of(missingRef.display()),
                    missingRef.display(), missingRef.quantity(), 1, null);
            missing.unresolved = missingRef.quantity();
            applyAvailability(missing, plan, missingRef.display());
            root.children.add(missing);
        }
        // The graph node list is the complete execution manifest. The legacy flat step list may
        // contain only the terminal operation, so it cannot recover omitted intermediate nodes.
        Set<ResourceLocation> renderedRecipes = new HashSet<>();
        collectRenderedRecipeIds(root, renderedRecipes);
        Set<Integer> rootReachableNodes = new HashSet<>();
        for (PlanGraphView.RootView demand : graph.roots()) {
            for (PlanGraphView.RootEdgeView edge : demand.allocations()) {
                collectReachableProducerNodes(edge.source(), index, rootReachableNodes);
            }
        }
        for (Integer nodeId : graph.topologicalOrder()) {
            PlanGraphView.NodeView graphNode = index.node(nodeId);
            if (graphNode == null || !rootReachableNodes.contains(nodeId)
                    || renderedRecipes.contains(graphNode.recipeId())) continue;
            PlanStep step = graphNode.asPlanStep();
            PlanGraphView.OutputView output = graphNode.outputs().stream().findFirst().orElse(null);
            ItemStack display = output != null && !output.display().isEmpty()
                    ? output.display().copyWithCount(1)
                    : graphNode.primaryOutput().copyWithCount(1);
            if (display.isEmpty()) continue;
            if (!budget.tryClaimNode(1)) {
                root.viewTruncated = true;
                break;
            }
            long quantity = output != null ? output.quantity()
                    : Math.max(1, display.getCount() * graphNode.executions());
            PlanTreeNode omitted = new PlanTreeNode(IngredientKey.of(display), display,
                    quantity, 1, step, nodeId);
            omitted.limited = step.alternatives().size() > maxTreeCandidates();
            applyAvailability(omitted, plan, display);
            expandedProducers.add(nodeId);
            for (GraphReference edge : mergeConsumerEdges(index, nodeId)) {
                PlanTreeNode child = buildGraphReference(edge.source(), edge.material(), edge.quantity(),
                        edge.role(), 2, index, new HashSet<>(Set.of(nodeId)),
                        expandedProducers, plan, budget);
                if (child == null) {
                    omitted.viewTruncated = true;
                    break;
                }
                omitted.children.add(child);
            }
            root.children.add(omitted);
            renderedRecipes.add(graphNode.recipeId());
        }
        mergeEquivalentProducedChildren(root);
        return new PlanTreeModel(root, budget.truncated());
    }

    private static void collectRenderedRecipeIds(PlanTreeNode node, Set<ResourceLocation> ids) {
        if (node.step != null) ids.add(node.step.recipeId());
        for (PlanTreeNode child : node.children) collectRenderedRecipeIds(child, ids);
    }

    private static void collectReachableProducerNodes(PlanGraphView.SourceView source,
                                                       GraphViewIndex index,
                                                       Set<Integer> reachable) {
        if (source == null || source.initial() || !reachable.add(source.producerNodeId())) return;
        for (GraphReference edge : mergeConsumerEdges(index, source.producerNodeId())) {
            collectReachableProducerNodes(edge.source(), index, reachable);
        }
    }

    private static PlanTreeNode buildGraphReference(PlanGraphView.SourceView source,
                                                     ItemStack material, long quantity,
                                                     DemandRole role, int depth,
                                                     GraphViewIndex index,
                                                     Set<Integer> path,
                                                     Set<Integer> expandedProducers,
                                                     PlanResponse plan,
                                                     BuildBudget budget) {
        if (!budget.tryClaimNode(depth)) return null;
        if (source.initial()) {
            PlanTreeNode leaf = new PlanTreeNode(IngredientKey.of(material), material,
                    quantity, depth, null, role);
            leaf.edgeQuantity = quantity;
            leaf.initialSource = true;
            applyAvailability(leaf, plan, material);
            return leaf;
        }

        PlanGraphView.NodeView producer = index.node(source.producerNodeId());
        if (producer == null) {
            PlanTreeNode broken = new PlanTreeNode(IngredientKey.of(material), material,
                    quantity, depth, null, role).markCycle();
            broken.edgeQuantity = quantity;
            return broken;
        }
        if (!path.add(producer.nodeId())) {
            PlanTreeNode cycle = new PlanTreeNode(IngredientKey.of(material), material,
                    quantity, depth, producer.asPlanStep(), producer.nodeId(), role).markCycle();
            cycle.edgeQuantity = quantity;
            applyAvailability(cycle, plan, material);
            return cycle;
        }

        // The edge material is the exact output port selected by the server. It may be a
        // secondary output or crafting remainder, so using primaryOutput here would show
        // the wrong item even though the logical producer node is the same.
        ItemStack display = material.isEmpty()
                ? producer.primaryOutput().copyWithCount(1) : material.copyWithCount(1);
        PlanTreeNode node = new PlanTreeNode(IngredientKey.of(display), display,
                quantity, depth, producer.asPlanStep(), producer.nodeId(), role);
        node.limited = node.step.alternatives().size() > maxTreeCandidates();
        node.edgeQuantity = quantity;
        for (PlanGraphView.OutputView output : producer.outputs()) {
            if (output.portIndex() == source.producerPortIndex()) {
                node.outputKindOrdinal = output.kindOrdinal();
                break;
            }
        }
        applyAvailability(node, plan, display);

        // A DAG producer may feed several consumers. The tree keeps a visual
        // reference at every edge, but expands that producer's input cost only
        // once so shared surplus is not counted once per downstream branch.
        if (!expandedProducers.add(producer.nodeId())) {
            path.remove(producer.nodeId());
            return node;
        }

        for (GraphReference edge : mergeConsumerEdges(index, producer.nodeId())) {
            PlanTreeNode child = buildGraphReference(edge.source(), edge.material(), edge.quantity(),
                    edge.role(), depth + 1, index, path, expandedProducers, plan, budget);
            if (child == null) {
                node.viewTruncated = true;
                break;
            }
            node.children.add(child);
        }
        // Unresolved demand is a separate portion of the input port. Keep it as
        // its own view reference even when the same port is partially supplied;
        // attaching it to an allocated child hides the conservation split.
        Map<DemandKey, UnresolvedReference> unresolved = new LinkedHashMap<>();
        for (PlanGraphView.UnresolvedView view : index.unresolvedFor(producer.nodeId())) {
            IngredientKey key = IngredientKey.of(view.display());
            DemandRole unresolvedRole = index.inputRole(producer.nodeId(),
                    view.consumerPortIndex());
            DemandKey demandKey = new DemandKey(key, unresolvedRole);
            unresolved.compute(demandKey, (ignored, existing) -> existing == null
                    ? new UnresolvedReference(view.display(), view.quantity(), unresolvedRole)
                    : existing.add(view.quantity()));
        }
        for (UnresolvedReference missingRef : unresolved.values()) {
            if (!budget.tryClaimNode(depth + 1)) {
                node.viewTruncated = true;
                break;
            }
            PlanTreeNode missing = new PlanTreeNode(IngredientKey.of(missingRef.display()),
                    missingRef.display(), missingRef.quantity(), depth + 1, null,
                    missingRef.role());
            missing.unresolved = missingRef.quantity();
            applyAvailability(missing, plan, missingRef.display());
            node.children.add(missing);
        }
        mergeEquivalentProducedChildren(node);
        path.remove(producer.nodeId());
        return node;
    }

    /**
     * Fold equivalent sibling references into one visual row.  Produced nodes
     * retain the recipe-aware key so different machine choices remain distinct;
     * raw-material leaves use their item/NBT identity and are always coalesced.
     * The server graph remains unchanged; this only aggregates the tree view.
     */
    private static void mergeEquivalentProducedChildren(PlanTreeNode parent) {
        Map<VisualKey, PlanTreeNode> merged = new LinkedHashMap<>();
        List<PlanTreeNode> result = new ArrayList<>();
        for (PlanTreeNode child : parent.children) {
            if (child.cycle) {
                result.add(child);
                continue;
            }
            VisualKey key = child.step == null
                    ? new MaterialVisualKey(child.key, child.initialSource, child.demandRole)
                    : new VisualRecipeKey(child.step.recipeId(),
                    child.step.modType() == null ? "" : child.step.modType().id(),
                    child.key, child.outputKindOrdinal, child.demandRole);
            PlanTreeNode existing = merged.get(key);
            if (existing == null) {
                merged.put(key, child);
                result.add(child);
                continue;
            }
            existing.amount = SaturatingLongMath.add(existing.amount, child.amount);
            existing.edgeQuantity = SaturatingLongMath.add(
                    existing.edgeQuantity, child.edgeQuantity);
            existing.unresolved = SaturatingLongMath.add(existing.unresolved, child.unresolved);
            // Availability is a shared inventory total, not a per-branch
            // quantity. Keep one copy; amount carries the summed branch demand.
            existing.available = Math.max(existing.available, child.available);
            existing.needed = Math.max(existing.needed, child.needed);
            existing.children.addAll(child.children);
            mergeEquivalentProducedChildren(existing);
        }
        parent.children.clear();
        parent.children.addAll(result);
    }

    private sealed interface VisualKey permits VisualRecipeKey, MaterialVisualKey {}

    private record VisualRecipeKey(ResourceLocation recipeId,
                                   String modType, IngredientKey output,
                                   int outputKindOrdinal,
                                   DemandRole demandRole) implements VisualKey {}

    private record MaterialVisualKey(IngredientKey material, boolean initialSource,
                                     DemandRole demandRole) implements VisualKey {}

    private static List<GraphReference> mergeRootEdges(List<PlanGraphView.RootEdgeView> edges) {
        Map<GraphReferenceKey, GraphReference> merged = new LinkedHashMap<>();
        for (PlanGraphView.RootEdgeView edge : edges) {
            mergeGraphReference(merged, edge.source(), edge.material(), edge.quantity(),
                    DemandRole.CONSUMED);
        }
        return List.copyOf(merged.values());
    }

    private static List<GraphReference> mergeConsumerEdges(GraphViewIndex index, int consumerNodeId) {
        Map<GraphReferenceKey, GraphReference> merged = new LinkedHashMap<>();
        for (PlanGraphView.EdgeView edge : index.edgesFor(consumerNodeId)) {
            mergeGraphReference(merged, edge.source(), edge.material(), edge.quantity(),
                    index.inputRole(consumerNodeId, edge.consumerPortIndex()));
        }
        return List.copyOf(merged.values());
    }

    private record ConsumerPortKey(int nodeId, int portIndex) {}

    private static final class GraphViewIndex {
        private final Map<Integer, PlanGraphView.NodeView> nodes;
        private final Map<Integer, List<PlanGraphView.EdgeView>> edgesByConsumer;
        private final Map<Integer, List<PlanGraphView.UnresolvedView>> unresolvedByConsumer;
        private final Map<ConsumerPortKey, DemandRole> inputRoles;

        private GraphViewIndex(Map<Integer, PlanGraphView.NodeView> nodes,
                               Map<Integer, List<PlanGraphView.EdgeView>> edgesByConsumer,
                               Map<Integer, List<PlanGraphView.UnresolvedView>> unresolvedByConsumer,
                               Map<ConsumerPortKey, DemandRole> inputRoles) {
            this.nodes = nodes;
            this.edgesByConsumer = edgesByConsumer;
            this.unresolvedByConsumer = unresolvedByConsumer;
            this.inputRoles = inputRoles;
        }

        static GraphViewIndex from(PlanGraphView graph) {
            Map<Integer, PlanGraphView.NodeView> nodes = new HashMap<>();
            Map<Integer, List<PlanGraphView.EdgeView>> edgesByConsumer = new HashMap<>();
            Map<Integer, List<PlanGraphView.UnresolvedView>> unresolvedByConsumer = new HashMap<>();
            Map<ConsumerPortKey, DemandRole> inputRoles = new HashMap<>();
            for (PlanGraphView.NodeView node : graph.nodes()) {
                nodes.put(node.nodeId(), node);
                for (PlanGraphView.InputView input : node.inputs()) {
                    inputRoles.put(new ConsumerPortKey(node.nodeId(), input.portIndex()), input.role());
                }
            }
            for (PlanGraphView.EdgeView edge : graph.edges()) {
                edgesByConsumer.computeIfAbsent(edge.consumerNodeId(), ignored -> new ArrayList<>())
                        .add(edge);
            }
            for (PlanGraphView.UnresolvedView unresolved : graph.unresolved()) {
                unresolvedByConsumer.computeIfAbsent(unresolved.consumerNodeId(),
                        ignored -> new ArrayList<>()).add(unresolved);
            }
            return new GraphViewIndex(nodes, edgesByConsumer, unresolvedByConsumer, inputRoles);
        }

        @Nullable
        PlanGraphView.NodeView node(int nodeId) {
            return nodes.get(nodeId);
        }

        List<PlanGraphView.EdgeView> edgesFor(int consumerNodeId) {
            return edgesByConsumer.getOrDefault(consumerNodeId, List.of());
        }

        List<PlanGraphView.UnresolvedView> unresolvedFor(int consumerNodeId) {
            return unresolvedByConsumer.getOrDefault(consumerNodeId, List.of());
        }

        DemandRole inputRole(int consumerNodeId, int consumerPortIndex) {
            return inputRoles.getOrDefault(
                    new ConsumerPortKey(consumerNodeId, consumerPortIndex),
                    DemandRole.CONSUMED);
        }
    }

    private static void mergeGraphReference(Map<GraphReferenceKey, GraphReference> merged,
                                            PlanGraphView.SourceView source,
                                            ItemStack material, long quantity, DemandRole role) {
        GraphReferenceKey key = new GraphReferenceKey(source, IngredientKey.of(material), role);
        merged.compute(key, (ignored, existing) -> existing == null
                ? new GraphReference(source, material, quantity, role)
                : existing.add(quantity));
    }

    private record GraphReferenceKey(PlanGraphView.SourceView source, IngredientKey material,
                                     DemandRole role) {}

    private record GraphReference(PlanGraphView.SourceView source, ItemStack material, long quantity,
                                  DemandRole role) {
        private GraphReference {
            material = material.copyWithCount(1);
        }

        private GraphReference add(long additional) {
            return new GraphReference(source, material,
                    SaturatingLongMath.add(quantity, additional), role);
        }
    }

    private record UnresolvedReference(ItemStack display, long quantity, DemandRole role) {
        private UnresolvedReference {
            display = display.copyWithCount(1);
        }

        private UnresolvedReference add(long additional) {
            return new UnresolvedReference(display,
                    SaturatingLongMath.add(quantity, additional), role);
        }
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
     * Aggregate every non-root node's demanded {@code amount} by item. Consumed inputs add across
     * the whole tree; reusable catalysts add within one recipe step and take the peak across steps.
     * This is the gross bill of materials for the planned executions. A consumer's demand
     * includes stored intermediate items, while producer inputs use the actual planned batches.
     * <p>
     * The server uses this to fill {@link PlanResponse#materials()} so the total-demand strip and
     * card material panel display exactly the numbers the tree renders, including intermediate
     * demand as well as external materials.
     */
    public static Map<IngredientKey, Long> grossDemandByKey(PlanTreeModel model) {
        Map<IngredientKey, Long> consumed = new LinkedHashMap<>();
        Map<IngredientKey, Long> catalystPeak = new LinkedHashMap<>();
        accumulateChildDemand(model.root, consumed, catalystPeak);
        Map<IngredientKey, Long> out = new LinkedHashMap<>(consumed);
        for (Map.Entry<IngredientKey, Long> entry : catalystPeak.entrySet()) {
            out.merge(entry.getKey(), entry.getValue(), SaturatingLongMath::add);
        }
        return out;
    }

    private static void accumulateChildDemand(PlanTreeNode parent,
                                              Map<IngredientKey, Long> consumed,
                                              Map<IngredientKey, Long> catalystPeak) {
        Map<IngredientKey, Long> catalystsForStep = new LinkedHashMap<>();
        for (PlanTreeNode child : parent.children) {
            if (child.demandRole == DemandRole.CATALYST) {
                catalystsForStep.merge(child.key, child.amount, SaturatingLongMath::add);
            } else {
                consumed.merge(child.key, child.amount, SaturatingLongMath::add);
            }
        }
        for (Map.Entry<IngredientKey, Long> entry : catalystsForStep.entrySet()) {
            catalystPeak.merge(entry.getKey(), entry.getValue(), Math::max);
        }
        for (PlanTreeNode child : parent.children) {
            accumulateChildDemand(child, consumed, catalystPeak);
        }
    }

    private static void buildChildren(PlanTreeNode parent,
                                      Map<IngredientKey, PlanStep> producers,
                                      Set<IngredientKey> pathStack,
                                      Set<PlanStep> expandedSteps, PlanResponse plan,
                                      BuildBudget budget) {
        PlanStep parentStep = parent.step != null ? parent.step : producers.get(parent.key);
        if (parentStep == null) return; // leaf — no producing step

        // 总需求可能由库存和新产物共同满足；原料只能按服务端实际执行次数展开。
        // 共享步骤保留各分支的需求引用，但与图模式一样只展开一次生产成本。
        if (!expandedSteps.add(parentStep)) return;
        long parentBatches = Math.max(1L, parentStep.batches());
        // Merge same-item inputs (e.g. the nine gold-ingot slots of a 3×3 recipe) into one
        // entry, summing counts — the tree shows "gold ingot ×9", not nine "×1" nodes.
        // Empty grid slots carry no ingredient and are skipped.
        LinkedHashMap<DemandKey, ItemStack> reps = new LinkedHashMap<>();
        LinkedHashMap<DemandKey, Long> counts = new LinkedHashMap<>();
        for (int inputIndex = 0; inputIndex < parentStep.inputs().size(); inputIndex++) {
            ItemStack input = parentStep.inputs().get(inputIndex);
            if (input.isEmpty()) continue;
            IngredientKey inputKey = IngredientKey.of(input);
            DemandKey demandKey = new DemandKey(inputKey, parentStep.inputRole(inputIndex));
            reps.putIfAbsent(demandKey, input);
            counts.merge(demandKey,
                    parentStep.totalInputCount(inputIndex, parentBatches),
                    SaturatingLongMath::add);
        }

        for (Map.Entry<DemandKey, ItemStack> e : reps.entrySet()) {
            IngredientKey inputKey = e.getKey().material();
            DemandRole role = e.getKey().role();
            ItemStack input = e.getValue();
            long amount = counts.get(e.getKey());
            PlanStep childStep = producers.get(inputKey);

            if (childStep != null) {
                if (!budget.tryClaimNode(parent.depth + 1)) {
                    parent.viewTruncated = true;
                    break;
                }
                if (!pathStack.add(inputKey)) {
                    // Same key already on this ancestor chain → genuine cycle.
                    PlanTreeNode cycleNode = new PlanTreeNode(inputKey, input, amount,
                            parent.depth + 1, null, role)
                            .markCycle();
                    parent.children.add(cycleNode);
                    continue;
                }

                PlanTreeNode child = new PlanTreeNode(
                        inputKey, childStep.output(), amount, parent.depth + 1, childStep, role);
                child.limited = childStep.alternatives().size()
                        > maxTreeCandidates();
                applyAvailability(child, plan, input);
                parent.children.add(child);

                buildChildren(child, producers, pathStack, expandedSteps, plan, budget);
                pathStack.remove(inputKey); // backtrack — sibling branches may reuse this material
            } else {
                if (!budget.tryClaimNode(parent.depth + 1)) {
                    parent.viewTruncated = true;
                    break;
                }
                PlanTreeNode leaf = new PlanTreeNode(
                        inputKey, input, amount, parent.depth + 1, null, role);
                applyAvailability(leaf, plan, input);
                parent.children.add(leaf);
            }
        }
    }

    private record DemandKey(IngredientKey material, DemandRole role) {}

    private static void applyAvailability(PlanTreeNode node, PlanResponse plan, ItemStack input) {
        PlanResponse.Availability a = plan.availability(input);
        if (a != null) {
            node.available = a.available();
            node.needed = a.needed();
        }
        if (node.step != null) {
            node.hints = EmbersMachineHints.tooltipKeys(node.step.modType()).stream()
                    .<Component>map(Component::translatable).toList();
            PlanResponse.StepIssue issue = plan.stepIssues().get(node.step.recipeId());
            if (issue != null) {
                node.warnings = issue.warnings();
                node.prerequisiteBlocked = issue.blocked();
            }
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
