package com.huanghuang.rsintegration.crafting.tree;

import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.crafting.graph.DemandRole;
import com.huanghuang.rsintegration.crafting.plan.PlanGraphView;
import com.huanghuang.rsintegration.crafting.plan.ParallelPlan;
import com.huanghuang.rsintegration.crafting.plan.PlanResponse;
import com.huanghuang.rsintegration.crafting.plan.PlanStep;
import com.huanghuang.rsintegration.crafting.quantity.SaturatingLongMath;
import com.huanghuang.rsintegration.mods.embers.EmbersMachineHints;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import java.util.ArrayList;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
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

    /** 执行图按分配边展示；无图预览只展开服务端记录的生产步骤引用。 */
    public static PlanTreeModel from(PlanResponse plan) {
        return from(plan, treeLimits());
    }

    static PlanTreeModel from(PlanResponse plan, int maxDepth, int maxNodes) {
        return from(plan, new TreeLimits(maxDepth, maxNodes));
    }

    private static PlanTreeModel from(PlanResponse plan, TreeLimits limits) {
        BuildBudget budget = new BuildBudget(limits);
        if (plan.graph() == null) return fromTrace(plan, budget);
        PlanTreeModel model = fromGraph(plan, budget);
        // 图节点的兄弟合并会改变成本展开的位置，整棵树建完后再标记。
        markReferenceNodes(model.root);
        attachRecipeOptions(model.root, plan.graph());
        return model;
    }

    private static void attachRecipeOptions(PlanTreeNode node, PlanGraphView graph) {
        node.recipeOptions = graph.recipeOptions().getOrDefault(node.key, List.of());
        for (PlanTreeNode child : node.children) attachRecipeOptions(child, graph);
    }

    /**
     * 标记引用节点：同一批产出的成本只展开一次，其他出现位置只是消耗引用。
     * <p>
     * 这里按"生产者身份"分组（图模式下是 {@link PlanTreeNode#graphNodeId}，旧版清单模式下是
     * {@link PlanStep} 本身）：组内存在带子节点的节点，则没有子节点的同组节点都是引用。
     */
    private static void markReferenceNodes(PlanTreeNode root) {
        Set<Object> expanded = new HashSet<>();
        collectExpandedProducers(root, expanded);
        applyReferenceFlags(root, expanded);
    }

    private static void collectExpandedProducers(PlanTreeNode node, Set<Object> expanded) {
        if (node.step != null && !node.children.isEmpty()) {
            if (node.producerNodeIds.isEmpty()) expanded.add(node.step);
            else node.producerNodeIds.forEach(id -> expanded.add("graph:" + id));
        }
        for (PlanTreeNode child : node.children) {
            collectExpandedProducers(child, expanded);
        }
    }

    private static void applyReferenceFlags(PlanTreeNode node, Set<Object> expanded) {
        // 显示上限截断的节点同样没有子节点，但那不是"成本已在别处展开"，不应画引用角标。
        if (node.step != null && node.children.isEmpty() && !node.viewTruncated
                && (node.producerNodeIds.isEmpty() ? expanded.contains(node.step)
                : node.producerNodeIds.stream().allMatch(id -> expanded.contains("graph:" + id)))) {
            node.referenceOnly = true;
        }
        for (PlanTreeNode child : node.children) {
            applyReferenceFlags(child, expanded);
        }
    }

    private static int maxTreeCandidates() {
        // 备选配方展示不再由配置限制；保留一个客户端渲染安全上限。
        return 8;
    }

    private static TreeLimits treeLimits() {
        // 兼容 1024 层依赖，另外为界面根、终端配方与原材料叶保留空间。
        return new TreeLimits(RSIntegrationConfig.DEFAULT_CRAFTING_MAX_DEPTH + 4, 20_000);
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

    private static PlanTreeModel fromTrace(PlanResponse plan, BuildBudget budget) {
        ItemStack target = plan.targetResult();
        List<PlanStep> cards = plan.standaloneSteps();
        int terminal = cards.size() - 1;
        for (; terminal >= 0; terminal--) {
            var step = cards.get(terminal);
            if (step.recipeId().toString().equals(plan.recipeId())
                    && ItemStack.isSameItemSameTags(target, step.output())) break;
        }
        PlanStep targetStep = terminal < 0 ? null : cards.get(terminal);
        PlanTreeNode root = new PlanTreeNode(
                IngredientKey.of(target), target,
                SaturatingLongMath.multiply(target.getCount(), Math.max(1L, plan.repeatCount())), 0, targetStep);
        if (targetStep != null) {
            root.productionAmount = targetStep.totalOutputCount();
            root.limited = targetStep.alternatives().size() > maxTreeCandidates();
        }
        applyAvailability(root, plan, target);

        if (targetStep != null) expandTrace(root, terminal, cards, new HashSet<>(), plan, budget);
        return new PlanTreeModel(root, budget.truncated());
    }

    private static PlanTreeModel fromGraph(PlanResponse plan, BuildBudget budget) {
        PlanGraphView graph = plan.graph();
        GraphViewIndex index = GraphViewIndex.from(graph);

        ItemStack target = plan.targetResult();
        PlanStep targetStep = null;
        if (plan.recipeId() != null) {
            for (PlanStep step : plan.standaloneSteps()) {
                if (plan.recipeId().equals(step.recipeId().toString())
                        && ItemStack.isSameItemSameTags(target, step.output())) {
                    targetStep = step;
                    break;
                }
            }
            if (targetStep == null) {
                for (var demand : graph.roots()) {
                    for (var allocation : demand.allocations()) {
                        if (allocation.source().initial()) continue;
                        var producer = index.node(allocation.source().producerNodeId());
                        if (producer != null && plan.recipeId().equals(producer.recipeId().toString())
                                && ItemStack.isSameItemSameTags(target, producer.primaryOutput())) {
                            targetStep = producer.asPlanStep();
                            break;
                        }
                    }
                    if (targetStep != null) break;
                }
            }
        }
        PlanTreeNode root = new PlanTreeNode(IngredientKey.of(target), target,
                target.getCount() * Math.max(1, plan.repeatCount()), 0, targetStep);
        if (targetStep != null) {
            root.productionAmount = targetStep.totalOutputCount();
            root.limited = targetStep.alternatives().size()
                    > maxTreeCandidates();
        }
        applyAvailability(root, plan, target);

        // Root allocations are explicit sinks. Merge repeated input slots backed by
        // the same producer output so a recipe that consumes four units from one
        // batch is rendered as one "x4" producer reference, not four "x1" nodes.
        Set<Integer> path = new HashSet<>();
        Set<Integer> expandedProducers = new HashSet<>();
        Map<DemandKey, UnresolvedReference> unresolvedRoots = new LinkedHashMap<>();
        for (PlanGraphView.RootView demand : graph.roots()) {
            if (demand.unresolvedQuantity() > 0) {
                DemandKey key = new DemandKey(IngredientKey.of(demand.display()), demand.role());
                unresolvedRoots.compute(key, (ignored, existing) -> existing == null
                        ? new UnresolvedReference(demand.display(), demand.unresolvedQuantity(),
                                demand.role())
                        : existing.add(demand.unresolvedQuantity()));
            }
        }
        for (GraphReference edge : mergeRootEdges(graph.roots())) {
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
                    missingRef.display(), missingRef.quantity(), 1, null, null, missingRef.role());
            missing.unresolved = missingRef.quantity();
            applyAvailability(missing, plan, missingRef.display());
            root.children.add(missing);
        }
        mergeEquivalentProducedChildren(root, index);
        collapseRepeatedStages(root, index);
        rebaseDepths(root, 0);
        return new PlanTreeModel(root, budget.truncated());
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
                    quantity, depth, index.stepFor(producer), producer.nodeId(), role).markCycle();
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
                quantity, depth, index.stepFor(producer), producer.nodeId(), role);
        node.limited = node.step.alternatives().size() > maxTreeCandidates();
        node.edgeQuantity = quantity;
        for (PlanGraphView.OutputView output : producer.outputs()) {
            if (output.portIndex() == source.producerPortIndex()) {
                node.outputKindOrdinal = output.kindOrdinal();
                node.productionAmount = output.quantity();
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
        mergeEquivalentProducedChildren(node, index);
        path.remove(producer.nodeId());
        return node;
    }

    /**
     * Fold equivalent sibling references into one visual row.  Produced nodes
     * retain the recipe-aware key so different machine choices remain distinct;
     * raw-material leaves use their item/NBT identity and are always coalesced.
     * The server graph remains unchanged; this only aggregates the tree view.
     */
    private static void mergeEquivalentProducedChildren(PlanTreeNode parent, GraphViewIndex index) {
        Map<VisualKey, PlanTreeNode> merged = new LinkedHashMap<>();
        List<PlanTreeNode> result = new ArrayList<>();
        for (PlanTreeNode child : parent.children) {
            if (child.cycle) {
                result.add(child);
                continue;
            }
            VisualKey key = child.step == null
                    ? new MaterialVisualKey(child.key, child.initialSource, child.demandRole)
                    : recipeVisualKey(child);
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
            existing.missingCount = Math.max(existing.missingCount, child.missingCount);
            existing.producerNodeIds.addAll(child.producerNodeIds);
            refreshMergedProduction(existing, index);
            existing.children.addAll(child.children);
            mergeEquivalentProducedChildren(existing, index);
        }
        parent.children.clear();
        parent.children.addAll(result);
    }

    private sealed interface VisualKey permits VisualRecipeKey, MaterialVisualKey {}

    private record VisualRecipeKey(ResourceLocation recipeId,
                                   String modType, IngredientKey output,
                                   int outputKindOrdinal,
                                   DemandRole demandRole,
                                   List<InputVisualKey> inputs, int width, int height,
                                   List<ResourceLocation> alternatives, List<String> routes) implements VisualKey {}

    private record InputVisualKey(IngredientKey material, int count, DemandRole role) {}

    private static VisualRecipeKey recipeVisualKey(PlanTreeNode node) {
        PlanStep step = node.step;
        List<InputVisualKey> inputs = new ArrayList<>();
        for (int i = 0; i < step.inputs().size(); i++) {
            ItemStack input = step.inputs().get(i);
            inputs.add(new InputVisualKey(IngredientKey.of(input), input.getCount(), step.inputRole(i)));
        }
        return new VisualRecipeKey(step.recipeId(), step.modType() == null ? "" : step.modType().id(),
                node.key, node.outputKindOrdinal, node.demandRole, inputs, step.recipeWidth(), step.recipeHeight(),
                step.alternatives(), step.alternativeModTypes());
    }

    /** 连续复制阶段只显示一个配方，种子回流边留在原始执行图中。 */
    private static void collapseRepeatedStages(PlanTreeNode parent, GraphViewIndex index) {
        for (PlanTreeNode child : parent.children) collapseRepeatedStages(child, index);
        if (parent.step == null || parent.cycle) return;
        VisualRecipeKey key = recipeVisualKey(parent);
        List<PlanTreeNode> children = new ArrayList<>();
        boolean collapsed = false;
        for (PlanTreeNode child : parent.children) {
            if (child.step != null && !child.cycle && key.equals(recipeVisualKey(child))) {
                parent.producerNodeIds.addAll(child.producerNodeIds);
                children.addAll(child.children);
                collapsed = true;
            } else children.add(child);
        }
        if (!collapsed) return;
        parent.children.clear();
        parent.children.addAll(children);
        refreshMergedProduction(parent, index);
        mergeEquivalentProducedChildren(parent, index);
    }

    private static void refreshMergedProduction(PlanTreeNode node, GraphViewIndex index) {
        if (node.step == null || node.producerNodeIds.isEmpty()) return;
        long batches = 0L;
        long production = 0L;
        List<ParallelPlan> plans = new ArrayList<>();
        for (int id : node.producerNodeIds) {
            PlanGraphView.NodeView producer = index.node(id);
            batches = SaturatingLongMath.add(batches, producer.executions());
            plans.add(producer.parallelPlan());
            for (PlanGraphView.OutputView output : producer.outputs()) {
                if (output.kindOrdinal() == node.outputKindOrdinal
                        && IngredientKey.of(output.display()).equals(node.key)) {
                    production = SaturatingLongMath.add(production, output.quantity());
                }
            }
        }
        for (int id : node.producerNodeIds) {
            for (PlanGraphView.EdgeView edge : index.edgesFor(id)) {
                if (!edge.source().initial()
                        && node.producerNodeIds.contains(edge.source().producerNodeId())
                        && IngredientKey.of(edge.material()).equals(node.key)) {
                    production -= edge.quantity();
                }
            }
        }
        PlanStep step = node.step;
        node.parallelPlan = ParallelPlan.mergeForDisplay(plans);
        node.step = new PlanStep(step.recipeId(), step.output(), batches, step.inputs(),
                step.alternatives(), step.modType(), step.depth(), step.hasOrSiblings(),
                step.recipeWidth(), step.recipeHeight(), step.alternativeModTypes(), step.inputRoles(),
                step.inferMode(), node.parallelPlan);
        node.productionAmount = Math.max(0L, production);
    }

    private static void rebaseDepths(PlanTreeNode node, int depth) {
        node.depth = depth;
        for (PlanTreeNode child : node.children) rebaseDepths(child, depth + 1);
    }

    private record MaterialVisualKey(IngredientKey material, boolean initialSource,
                                     DemandRole demandRole) implements VisualKey {}

    private static List<GraphReference> mergeRootEdges(List<PlanGraphView.RootView> roots) {
        Map<GraphReferenceKey, GraphReference> merged = new LinkedHashMap<>();
        for (PlanGraphView.RootView root : roots) {
            for (PlanGraphView.RootEdgeView edge : root.allocations()) {
                mergeGraphReference(merged, edge.source(), edge.material(), edge.quantity(), root.role());
            }
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
            return new GraphViewIndex(nodes, edgesByConsumer, unresolvedByConsumer,
                    inputRoles);
        }

        PlanStep stepFor(PlanGraphView.NodeView node) {
            return node.asPlanStep();
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
        for (int id : node.producerNodeIds) counts.merge(id, 1, Integer::sum);
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

    private record TraceKey(IngredientKey material, DemandRole role, int producer) {}

    private static void expandTrace(PlanTreeNode parent, int ordinal, List<PlanStep> cards,
                                    Set<Integer> expanded, PlanResponse plan, BuildBudget budget) {
        if (!expanded.add(ordinal)) {
            parent.referenceOnly = true;
            return;
        }
        PlanStep step = cards.get(ordinal);
        Map<TraceKey, Long> quantities = new LinkedHashMap<>();
        if (step.traceInputs() != null) {
            for (var input : step.traceInputs()) {
                quantities.merge(new TraceKey(IngredientKey.of(input.material()), input.role(), input.producer()),
                        input.quantity(), SaturatingLongMath::add);
            }
        } else {
            // 没有轨迹时只展示这张卡片自身的用料，不向其他卡片推测来源。
            for (int i = 0; i < step.inputs().size(); i++) {
                if (step.inputs().get(i).isEmpty()) continue;
                quantities.merge(new TraceKey(IngredientKey.of(step.inputs().get(i)), step.inputRole(i), -1),
                        step.totalInputCount(i, step.batches()), SaturatingLongMath::add);
            }
        }
        for (var entry : quantities.entrySet()) {
            if (!budget.tryClaimNode(parent.depth + 1)) {
                parent.viewTruncated = true;
                break;
            }
            var key = entry.getKey();
            PlanStep producer = key.producer() >= 0 && key.producer() < ordinal ? cards.get(key.producer()) : null;
            var material = key.material().stack(1);
            var child = new PlanTreeNode(key.material(), material, entry.getValue(), parent.depth + 1, producer, key.role());
            applyAvailability(child, plan, material);
            parent.children.add(child);
            if (producer != null) {
                child.productionAmount = producer.totalOutputCount();
                child.limited = producer.alternatives().size() > maxTreeCandidates();
                expandTrace(child, key.producer(), cards, expanded, plan, budget);
            }
        }
    }

    private record DemandKey(IngredientKey material, DemandRole role) {}

    private static void applyAvailability(PlanTreeNode node, PlanResponse plan, ItemStack input) {
        PlanResponse.Availability a = plan.availability(input);
        if (a != null) {
            node.available = a.available();
            node.needed = a.needed();
            node.missingCount = a.missingCount();
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
