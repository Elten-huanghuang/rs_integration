package com.huanghuang.rsintegration.crafting.tree;

import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.crafting.graph.DemandRole;
import com.huanghuang.rsintegration.crafting.plan.PlanGraphView;
import com.huanghuang.rsintegration.crafting.plan.PlanResponse;
import com.huanghuang.rsintegration.crafting.plan.PlanStep;
import net.minecraft.resources.ResourceLocation;
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
        if (plan.graph() != null
                && (!plan.graph().nodes().isEmpty() || !plan.graph().roots().isEmpty())) {
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
        buildChildren(root, producerByOutput, pathStack, expandedSteps, plan);
        retainUnlinkedLegacySteps(root, plan, producerByOutput, expandedSteps);
        return new PlanTreeModel(root);
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
            Map<IngredientKey, PlanStep> producers, Set<PlanStep> expandedSteps) {
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
            int amount = Math.max(1, step.totalOutputCount());
            PlanTreeNode unlinked = new PlanTreeNode(IngredientKey.of(output), output,
                    amount, 1, step);
            unlinked.limited = step.alternatives().size() > maxTreeCandidates();
            applyAvailability(unlinked, plan, output);
            Set<IngredientKey> path = new LinkedHashSet<>();
            path.add(unlinked.key);
            buildChildren(unlinked, producers, path, expandedSteps, plan);
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
            root.children.add(buildGraphReference(edge.source(), edge.material(), edge.quantity(),
                    edge.role(), 1, graph, nodes, path, expandedProducers, plan));
        }
        for (UnresolvedReference missingRef : unresolvedRoots.values()) {
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
                collectReachableProducerNodes(edge.source(), graph, rootReachableNodes);
            }
        }
        for (Integer nodeId : graph.topologicalOrder()) {
            PlanGraphView.NodeView graphNode = nodes.get(nodeId);
            if (graphNode == null || !rootReachableNodes.contains(nodeId)
                    || renderedRecipes.contains(graphNode.recipeId())) continue;
            PlanStep step = graphNode.asPlanStep();
            PlanGraphView.OutputView output = graphNode.outputs().stream().findFirst().orElse(null);
            ItemStack display = output != null && !output.display().isEmpty()
                    ? output.display().copyWithCount(1)
                    : graphNode.primaryOutput().copyWithCount(1);
            if (display.isEmpty()) continue;
            int quantity = output != null ? output.quantity()
                    : Math.max(1, display.getCount() * graphNode.executions());
            PlanTreeNode omitted = new PlanTreeNode(IngredientKey.of(display), display,
                    quantity, 1, step, nodeId);
            omitted.limited = step.alternatives().size() > maxTreeCandidates();
            applyAvailability(omitted, plan, display);
            expandedProducers.add(nodeId);
            for (GraphReference edge : mergeConsumerEdges(graph, nodeId)) {
                omitted.children.add(buildGraphReference(edge.source(), edge.material(), edge.quantity(),
                        edge.role(), 2, graph, nodes, new HashSet<>(Set.of(nodeId)),
                        expandedProducers, plan));
            }
            root.children.add(omitted);
            renderedRecipes.add(graphNode.recipeId());
        }
        mergeEquivalentProducedChildren(root);
        return new PlanTreeModel(root);
    }

    private static void collectRenderedRecipeIds(PlanTreeNode node, Set<ResourceLocation> ids) {
        if (node.step != null) ids.add(node.step.recipeId());
        for (PlanTreeNode child : node.children) collectRenderedRecipeIds(child, ids);
    }

    private static void collectReachableProducerNodes(PlanGraphView.SourceView source,
                                                       PlanGraphView graph,
                                                       Set<Integer> reachable) {
        if (source == null || source.initial() || !reachable.add(source.producerNodeId())) return;
        for (GraphReference edge : mergeConsumerEdges(graph, source.producerNodeId())) {
            collectReachableProducerNodes(edge.source(), graph, reachable);
        }
    }

    private static PlanTreeNode buildGraphReference(PlanGraphView.SourceView source,
                                                     ItemStack material, int quantity,
                                                     DemandRole role, int depth,
                                                     PlanGraphView graph,
                                                     Map<Integer, PlanGraphView.NodeView> nodes,
                                                     Set<Integer> path,
                                                     Set<Integer> expandedProducers,
                                                     PlanResponse plan) {
        if (source.initial()) {
            PlanTreeNode leaf = new PlanTreeNode(IngredientKey.of(material), material,
                    quantity, depth, null, role);
            leaf.edgeQuantity = quantity;
            leaf.initialSource = true;
            applyAvailability(leaf, plan, material);
            return leaf;
        }

        PlanGraphView.NodeView producer = nodes.get(source.producerNodeId());
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

        for (GraphReference edge : mergeConsumerEdges(graph, producer.nodeId())) {
            PlanTreeNode child = buildGraphReference(edge.source(), edge.material(), edge.quantity(),
                    edge.role(), depth + 1, graph, nodes, path, expandedProducers, plan);
            node.children.add(child);
        }
        // Unresolved demand is a separate portion of the input port. Keep it as
        // its own view reference even when the same port is partially supplied;
        // attaching it to an allocated child hides the conservation split.
        Map<DemandKey, UnresolvedReference> unresolved = new LinkedHashMap<>();
        for (PlanGraphView.UnresolvedView view : graph.unresolved()) {
            if (view.consumerNodeId() != producer.nodeId()) continue;
            IngredientKey key = IngredientKey.of(view.display());
            DemandRole unresolvedRole = inputRole(graph, producer.nodeId(),
                    view.consumerPortIndex());
            DemandKey demandKey = new DemandKey(key, unresolvedRole);
            unresolved.compute(demandKey, (ignored, existing) -> existing == null
                    ? new UnresolvedReference(view.display(), view.quantity(), unresolvedRole)
                    : existing.add(view.quantity()));
        }
        for (UnresolvedReference missingRef : unresolved.values()) {
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
            existing.amount = saturatingAdd(existing.amount, child.amount);
            existing.edgeQuantity = saturatingAdd(existing.edgeQuantity, child.edgeQuantity);
            existing.unresolved = saturatingAdd(existing.unresolved, child.unresolved);
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

    private static List<GraphReference> mergeConsumerEdges(PlanGraphView graph, int consumerNodeId) {
        Map<GraphReferenceKey, GraphReference> merged = new LinkedHashMap<>();
        for (PlanGraphView.EdgeView edge : graph.edges()) {
            if (edge.consumerNodeId() != consumerNodeId) continue;
            mergeGraphReference(merged, edge.source(), edge.material(), edge.quantity(),
                    inputRole(graph, consumerNodeId, edge.consumerPortIndex()));
        }
        return List.copyOf(merged.values());
    }

    private static DemandRole inputRole(PlanGraphView graph, int consumerNodeId,
                                        int consumerPortIndex) {
        PlanGraphView.NodeView consumer = graph.node(consumerNodeId);
        if (consumer == null) return DemandRole.CONSUMED;
        for (PlanGraphView.InputView input : consumer.inputs()) {
            if (input.portIndex() == consumerPortIndex) return input.role();
        }
        return DemandRole.CONSUMED;
    }

    private static void mergeGraphReference(Map<GraphReferenceKey, GraphReference> merged,
                                            PlanGraphView.SourceView source,
                                            ItemStack material, int quantity, DemandRole role) {
        GraphReferenceKey key = new GraphReferenceKey(source, IngredientKey.of(material), role);
        merged.compute(key, (ignored, existing) -> existing == null
                ? new GraphReference(source, material, quantity, role)
                : existing.add(quantity));
    }

    private record GraphReferenceKey(PlanGraphView.SourceView source, IngredientKey material,
                                     DemandRole role) {}

    private record GraphReference(PlanGraphView.SourceView source, ItemStack material, int quantity,
                                  DemandRole role) {
        private GraphReference {
            material = material.copyWithCount(1);
        }

        private GraphReference add(int additional) {
            return new GraphReference(source, material, saturatingAdd(quantity, additional), role);
        }
    }

    private record UnresolvedReference(ItemStack display, int quantity, DemandRole role) {
        private UnresolvedReference {
            display = display.copyWithCount(1);
        }

        private UnresolvedReference add(int additional) {
            return new UnresolvedReference(display, saturatingAdd(quantity, additional), role);
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
    public static Map<IngredientKey, Integer> grossDemandByKey(PlanTreeModel model) {
        Map<IngredientKey, Integer> consumed = new LinkedHashMap<>();
        Map<IngredientKey, Integer> catalystPeak = new LinkedHashMap<>();
        accumulateChildDemand(model.root, consumed, catalystPeak);
        Map<IngredientKey, Integer> out = new LinkedHashMap<>(consumed);
        for (Map.Entry<IngredientKey, Integer> entry : catalystPeak.entrySet()) {
            out.merge(entry.getKey(), entry.getValue(), PlanTreeModel::saturatingAdd);
        }
        return out;
    }

    private static void accumulateChildDemand(PlanTreeNode parent,
                                              Map<IngredientKey, Integer> consumed,
                                              Map<IngredientKey, Integer> catalystPeak) {
        Map<IngredientKey, Integer> catalystsForStep = new LinkedHashMap<>();
        for (PlanTreeNode child : parent.children) {
            if (child.demandRole == DemandRole.CATALYST) {
                catalystsForStep.merge(child.key, child.amount, PlanTreeModel::saturatingAdd);
            } else {
                consumed.merge(child.key, child.amount, PlanTreeModel::saturatingAdd);
            }
        }
        for (Map.Entry<IngredientKey, Integer> entry : catalystsForStep.entrySet()) {
            catalystPeak.merge(entry.getKey(), entry.getValue(), Math::max);
        }
        for (PlanTreeNode child : parent.children) {
            accumulateChildDemand(child, consumed, catalystPeak);
        }
    }

    private static void buildChildren(PlanTreeNode parent,
                                      Map<IngredientKey, PlanStep> producers,
                                      Set<IngredientKey> pathStack,
                                      Set<PlanStep> expandedSteps, PlanResponse plan) {
        PlanStep parentStep = parent.step != null ? parent.step : producers.get(parent.key);
        if (parentStep == null) return; // leaf — no producing step

        // 总需求可能由库存和新产物共同满足；原料只能按服务端实际执行次数展开。
        // 共享步骤保留各分支的需求引用，但与图模式一样只展开一次生产成本。
        if (!expandedSteps.add(parentStep)) return;
        int parentBatches = Math.max(1, parentStep.batches());
        parent.batches = parentBatches;
        // Merge same-item inputs (e.g. the nine gold-ingot slots of a 3×3 recipe) into one
        // entry, summing counts — the tree shows "gold ingot ×9", not nine "×1" nodes.
        // Empty grid slots carry no ingredient and are skipped.
        LinkedHashMap<DemandKey, ItemStack> reps = new LinkedHashMap<>();
        LinkedHashMap<DemandKey, Integer> counts = new LinkedHashMap<>();
        for (int inputIndex = 0; inputIndex < parentStep.inputs().size(); inputIndex++) {
            ItemStack input = parentStep.inputs().get(inputIndex);
            if (input.isEmpty()) continue;
            IngredientKey inputKey = IngredientKey.of(input);
            DemandKey demandKey = new DemandKey(inputKey, parentStep.inputRole(inputIndex));
            reps.putIfAbsent(demandKey, input);
            counts.merge(demandKey,
                    parentStep.totalInputCount(inputIndex, parentBatches),
                    PlanTreeModel::saturatingAdd);
        }

        for (Map.Entry<DemandKey, ItemStack> e : reps.entrySet()) {
            IngredientKey inputKey = e.getKey().material();
            DemandRole role = e.getKey().role();
            ItemStack input = e.getValue();
            int amount = counts.get(e.getKey());
            PlanStep childStep = producers.get(inputKey);

            if (childStep != null) {
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

                buildChildren(child, producers, pathStack, expandedSteps, plan);
                pathStack.remove(inputKey); // backtrack — sibling branches may reuse this material
            } else {
                PlanTreeNode leaf = new PlanTreeNode(
                        inputKey, input, amount, parent.depth + 1, null, role);
                applyAvailability(leaf, plan, input);
                parent.children.add(leaf);
            }
        }
    }

    private static int saturatingAdd(int left, int right) {
        long total = (long) left + right;
        return total >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) total;
    }

    private record DemandKey(IngredientKey material, DemandRole role) {}

    private static void applyAvailability(PlanTreeNode node, PlanResponse plan, ItemStack input) {
        PlanResponse.Availability a = plan.availability(input);
        if (a != null) {
            node.available = a.available();
            node.needed = a.needed();
        }
        if (node.step != null) {
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
