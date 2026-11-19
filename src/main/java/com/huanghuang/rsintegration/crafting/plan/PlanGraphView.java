package com.huanghuang.rsintegration.crafting.plan;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.crafting.graph.CraftNode;
import com.huanghuang.rsintegration.crafting.graph.CraftPlanGraph;
import com.huanghuang.rsintegration.crafting.graph.GraphWork;
import com.huanghuang.rsintegration.crafting.graph.DemandRole;
import com.huanghuang.rsintegration.crafting.graph.MaterialSource;
import com.huanghuang.rsintegration.crafting.graph.NodeId;
import com.huanghuang.rsintegration.crafting.graph.OutputKind;
import com.huanghuang.rsintegration.crafting.quantity.SaturatingLongMath;
import com.huanghuang.rsintegration.crafting.tree.IngredientKey;
import com.huanghuang.rsintegration.magic.MagicResourceCost;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ObjLongConsumer;

/**
 * Client-safe, server-authored material-flow DAG carried alongside the legacy
 * PlanStep list. The client may render it but never sends it back as authority;
 * confirmation still asks the server to resolve a fresh plan.
 *
 * <p>The DTO deliberately contains display stacks rather than Ingredient
 * predicates. Predicate matching and NBT policy stay server-side; the wire only
 * needs stable node/port identity, exact chosen material, quantities, and source
 * provenance to render shared edges correctly.</p>
 */
public record PlanGraphView(
        int version,
        List<NodeView> nodes,
        List<EdgeView> edges,
        List<RootView> roots,
        List<UnresolvedView> unresolved,
        List<Integer> topologicalOrder,
        List<MagicResourceCost> magicCosts,
        Map<IngredientKey, List<RecipeOption>> recipeOptions
) {
    public PlanGraphView(int version, List<NodeView> nodes, List<EdgeView> edges,
                         List<RootView> roots, List<UnresolvedView> unresolved,
                         List<Integer> topologicalOrder, List<MagicResourceCost> magicCosts) {
        this(version, nodes, edges, roots, unresolved, topologicalOrder, magicCosts, Map.of());
    }

    public PlanGraphView(int version, List<NodeView> nodes, List<EdgeView> edges,
                         List<RootView> roots, List<UnresolvedView> unresolved,
                         List<Integer> topologicalOrder) {
        this(version, nodes, edges, roots, unresolved, topologicalOrder, List.of());
    }

    public PlanGraphView {
        nodes = List.copyOf(nodes);
        edges = List.copyOf(edges);
        roots = List.copyOf(roots);
        unresolved = List.copyOf(unresolved);
        topologicalOrder = List.copyOf(topologicalOrder);
        magicCosts = MagicResourceCost.merge(magicCosts);
        Map<IngredientKey, List<RecipeOption>> detached = new LinkedHashMap<>();
        recipeOptions.forEach((material, options) -> detached.put(material, List.copyOf(options)));
        recipeOptions = Collections.unmodifiableMap(detached);
    }

    public static PlanGraphView from(CraftPlanGraph graph) {
        return from(graph, Map.of());
    }

    /**
     * @param parallelPlans 计划阶段估算的节点并行分派快照；没有估算的节点为 null。
     */
    public static PlanGraphView from(CraftPlanGraph graph,
                                     Map<Integer, ParallelPlan> parallelPlans) {
        Projection work = new Projection(graph, parallelPlans);
        work.advance(Long.MAX_VALUE);
        return work.result();
    }

    public static final class Projection extends GraphWork {
        private PlanGraphView result;

        public Projection(CraftPlanGraph graph, Map<Integer, ParallelPlan> parallelPlans) {
            List<NodeView> nodes = new ArrayList<>(graph.nodes().size());
            scan(graph.nodes()::iterator, node -> {
                ItemStack primary = node.outputs().stream()
                        .filter(output -> output.kind() == OutputKind.PRIMARY
                                || output.kind() == OutputKind.DYNAMIC)
                        .findFirst()
                        .map(output -> output.material().toStack(
                                Math.max(1, SaturatingLongMath.clampToInt(
                                        output.quantity() / Math.max(1, node.executions())))))
                        .orElseGet(() -> node.syntheticOutput() != null
                                ? node.syntheticOutput().copy() : ItemStack.EMPTY);
                List<InputView> inputs = node.inputs().stream()
                        .map(input -> new InputView(input.id().index(), input.displayHint(),
                                input.quantity(), input.role().ordinal()))
                        .toList();
                List<OutputView> outputs = node.outputs().stream()
                    .map(output -> new OutputView(output.id().index(),
                                output.material().toStack(SaturatingLongMath.clampToInt(output.quantity())),
                                output.quantity(), output.kind().ordinal()))
                        .toList();
                nodes.add(new NodeView(node.id().value(), node.recipeId(), node.modTypeId(),
                        node.executions(), primary, node.alternativeIds(), node.alternativeModTypeIds(),
                        inputs, outputs, parallelPlans.get(node.id().value())));
            });

            List<EdgeView> edges = new ArrayList<>(graph.allocations().size());
            scan(graph.allocations()::iterator, allocation -> {
                SourceView source = source(allocation.source());
                edges.add(new EdgeView(allocation.consumer().nodeId().value(),
                        allocation.consumer().index(), source,
                        allocation.material().toStack(
                                SaturatingLongMath.clampToInt(allocation.quantity())),
                        allocation.quantity()));
            });

            List<RootView> roots = new ArrayList<>();
            scan(graph.rootDemands()::iterator, root -> roots.add(new RootView(root.displayHint(), root.quantity(), root.unresolvedQuantity(),
                            root.allocations().stream()
                                    .map(allocation -> new RootEdgeView(source(allocation.source()),
                                            allocation.material().toStack(
                                                    SaturatingLongMath.clampToInt(allocation.quantity())),
                                            allocation.quantity()))
                                    .toList(), root.role().ordinal())));

            List<UnresolvedView> unresolved = new ArrayList<>();
            scan(graph.unresolvedDemands()::iterator, missing -> unresolved.add(new UnresolvedView(missing.consumer().nodeId().value(),
                            missing.consumer().index(), missing.displayHint(), missing.quantity())));
            once(() -> result = new PlanGraphView(graph.version(), nodes, edges, roots, unresolved,
                    graph.topologicalOrder().stream().map(NodeId::value).toList(),
                    graph.magicCosts()));
        }

        public PlanGraphView result() {
            requireComplete();
            return result;
        }
    }

    private static SourceView source(MaterialSource source) {
        if (source instanceof MaterialSource.ProducerOutput producer) {
            return SourceView.fromProducer(producer.outputPort().nodeId().value(),
                    producer.outputPort().index());
        }
        return SourceView.fromInitial();
    }

    @Nullable
    public NodeView node(int nodeId) {
        for (NodeView node : nodes) if (node.nodeId() == nodeId) return node;
        return null;
    }

    /** 只枚举已经分配到初始库存的材料，包括直接供给目标配方的材料。 */
    public void visitInitialAllocations(ObjLongConsumer<ItemStack> accept) {
        for (EdgeView edge : edges) {
            if (edge.source().initial()) accept.accept(edge.material(), edge.quantity());
        }
        for (RootView root : roots) {
            for (RootEdgeView edge : root.allocations()) {
                if (edge.source().initial()) accept.accept(edge.material(), edge.quantity());
            }
        }
    }

    /** 只枚举外部承担的数量，生产者的输出不再计入初始库存。 */
    public void visitExternalDemand(ObjLongConsumer<ItemStack> accept) {
        visitInitialAllocations(accept);
        visitUnresolvedDemand(accept);
    }

    /** 展示物品只是未分配需求的代表，不能据此认定现货已通过配方匹配。 */
    public void visitUnresolvedDemand(ObjLongConsumer<ItemStack> accept) {
        for (RootView root : roots) {
            if (root.unresolvedQuantity() > 0)
                accept.accept(root.display(), root.unresolvedQuantity());
        }
        for (UnresolvedView input : unresolved) accept.accept(input.display(), input.quantity());
    }

    public boolean hasUnresolvedDemands() {
        return !unresolved.isEmpty() || roots.stream().anyMatch(root -> root.unresolvedQuantity() > 0);
    }

    /** 卡片和递归树共用已经通过服务端可用性检查的候选，不改动执行节点或材料边。 */
    public PlanGraphView withNodePresentations(Map<Integer, PlanStep> byNode) {
        List<NodeView> enriched = nodes.stream().map(node -> {
            PlanStep step = byNode.get(node.nodeId());
            return step == null ? node : node.withPresentation(step);
        }).toList();
        return new PlanGraphView(version, enriched, edges, roots, unresolved,
                topologicalOrder, magicCosts, recipeOptions);
    }

    /** 库存材料的可选生产者独立于执行节点，不能伪造一条待生产步骤。 */
    public PlanGraphView withRecipeOptions(Map<IngredientKey, List<RecipeOption>> options) {
        return new PlanGraphView(version, nodes, edges, roots, unresolved,
                topologicalOrder, magicCosts, options);
    }

    public record RecipeOption(ResourceLocation recipeId, String modTypeId) {}

    public record NodeView(int nodeId, ResourceLocation recipeId, String modTypeId,
                           int executions, ItemStack primaryOutput,
                           List<ResourceLocation> alternativeIds,
                           List<String> alternativeModTypeIds,
                           List<InputView> inputs, List<OutputView> outputs,
                           @Nullable ParallelPlan parallelPlan, Grid grid) {
        public NodeView(int nodeId, ResourceLocation recipeId, String modTypeId,
                        int executions, ItemStack primaryOutput,
                        List<ResourceLocation> alternativeIds, List<String> alternativeModTypeIds,
                        List<InputView> inputs, List<OutputView> outputs, @Nullable ParallelPlan parallelPlan) {
            this(nodeId, recipeId, modTypeId, executions, primaryOutput, alternativeIds,
                    alternativeModTypeIds, inputs, outputs, parallelPlan, Grid.NONE);
        }
        public NodeView(int nodeId, ResourceLocation recipeId, String modTypeId,
                        int executions, ItemStack primaryOutput,
                        List<ResourceLocation> alternativeIds,
                        List<String> alternativeModTypeIds,
                        List<InputView> inputs, List<OutputView> outputs) {
            this(nodeId, recipeId, modTypeId, executions, primaryOutput,
                    alternativeIds, alternativeModTypeIds, inputs, outputs, null);
        }

        public NodeView(int nodeId, ResourceLocation recipeId, String modTypeId,
                        int executions, ItemStack primaryOutput,
                        List<InputView> inputs, List<OutputView> outputs) {
            this(nodeId, recipeId, modTypeId, executions, primaryOutput,
                    List.of(), List.of(), inputs, outputs, null);
        }

        public NodeView {
            primaryOutput = primaryOutput.copy();
            alternativeIds = List.copyOf(alternativeIds);
            alternativeModTypeIds = List.copyOf(alternativeModTypeIds);
            inputs = List.copyOf(inputs);
            outputs = List.copyOf(outputs);
            grid.validate(inputs, executions);
        }

        public NodeView withPresentation(PlanStep step) {
            if (!recipeId.equals(step.recipeId())) throw new IllegalArgumentException("配方与节点不符");
            return new NodeView(nodeId, recipeId, modTypeId, executions, primaryOutput,
                    step.alternatives(), step.alternativeModTypes(), inputs, outputs, parallelPlan,
                    Grid.bind(step, inputs, executions));
        }

        public PlanStep asPlanStep() {
            int runCount = Math.max(1, executions);
            List<ItemStack> displayInputs = inputs.stream()
                    .map(input -> input.display().copyWithCount(
                            SaturatingLongMath.clampToInt(input.perExecutionQuantity(runCount))))
                    .toList();
            List<DemandRole> inputRoles = inputs.stream().map(InputView::role).toList();
            if (!grid.slots().isEmpty()) {
                displayInputs = grid.slots().stream().map(slot -> slot.count() == 0 ? ItemStack.EMPTY
                        : inputs.get(slot.input()).display().copyWithCount(slot.count())).toList();
                inputRoles = grid.slots().stream().map(slot -> slot.count() == 0 ? DemandRole.CONSUMED
                        : inputs.get(slot.input()).role()).toList();
            }
            return new PlanStep(recipeId, primaryOutput.copy(), runCount,
                    displayInputs, alternativeIds, ModType.byId(modTypeId), grid.depth(),
                    !alternativeIds.isEmpty(), grid.width(), grid.height(), alternativeModTypeIds, inputRoles,
                    false, parallelPlan);
        }
    }

    /** 槽位只引用本节点的输入及其份额；物品、NBT 和角色不在展示协议中重复存储。 */
    public record GridSlot(int input, int count) {}

    public record Grid(int width, int height, int depth, List<GridSlot> slots) {
        static final Grid NONE = new Grid(0, 0, 0, List.of());

        public Grid {
            slots = List.copyOf(slots);
            if (depth < 0 || width < 0 || height < 0 || width > 3 || height > 3
                    || (width == 0) != (height == 0) || slots.size() > width * height
                    || slots.isEmpty() != (width == 0)) throw new IllegalArgumentException("预览网格尺寸无效");
        }

        private void validate(List<InputView> inputs, int executions) {
            if (slots.isEmpty()) return;
            long[] remaining = quantities(inputs, executions);
            for (GridSlot slot : slots) {
                if (slot.count == 0 && slot.input == -1) continue;
                if (slot.input < 0 || slot.input >= remaining.length || slot.count <= 0
                        || (remaining[slot.input] -= slot.count) < 0)
                    throw new IllegalArgumentException("预览网格引用无效");
            }
            for (long count : remaining) if (count != 0) throw new IllegalArgumentException("预览网格数量不符");
        }

        private static long[] quantities(List<InputView> inputs, int executions) {
            return inputs.stream().mapToLong(input -> input.perExecutionQuantity(Math.max(1, executions))).toArray();
        }

        private static Grid bind(PlanStep presentation, List<InputView> inputs, int executions) {
            Grid linear = presentation.depth() == 0 ? NONE : new Grid(0, 0, presentation.depth(), List.of());
            int width = presentation.recipeWidth(), height = presentation.recipeHeight();
            if (width < 1 || height < 1 || width > 3 || height > 3 || presentation.inputs().isEmpty()
                    || presentation.inputs().size() > width * height) return linear;
            long[] remaining = quantities(inputs, executions);
            List<GridSlot> slots = new ArrayList<>();
            for (int slot = 0; slot < presentation.inputs().size(); slot++) {
                ItemStack template = presentation.inputs().get(slot);
                if (template.isEmpty()) {
                    slots.add(new GridSlot(-1, 0));
                    continue;
                }
                int matched = -1;
                for (int i = 0; i < inputs.size(); i++) {
                    if (remaining[i] >= template.getCount()
                            && inputs.get(i).role() == presentation.inputRole(slot)
                            && ItemStack.isSameItemSameTags(template, inputs.get(i).display())) {
                        matched = i;
                        break;
                    }
                }
                if (matched < 0) return linear;
                slots.add(new GridSlot(matched, template.getCount()));
                remaining[matched] -= template.getCount();
            }
            for (long count : remaining) if (count != 0L) return linear;
            return new Grid(width, height, presentation.depth(), slots);
        }
    }

    public record InputView(int portIndex, ItemStack display, long quantity, int roleOrdinal) {
        public InputView { display = display.copyWithCount(1); }

        private long perExecutionQuantity(int executions) {
            if (roleOrdinal == DemandRole.CATALYST.ordinal()
                    || roleOrdinal == DemandRole.FUEL.ordinal()
                    || roleOrdinal == DemandRole.DURABILITY.ordinal()) return quantity;
            return Math.max(1L, SaturatingLongMath.ceilDiv(quantity, executions));
        }

        public DemandRole role() {
            DemandRole[] roles = DemandRole.values();
            return roleOrdinal >= 0 && roleOrdinal < roles.length
                    ? roles[roleOrdinal] : DemandRole.CONSUMED;
        }
    }

    public record OutputView(int portIndex, ItemStack display, long quantity, int kindOrdinal) {
        public OutputView { display = display.copyWithCount(1); }
        public OutputKind kind() {
            OutputKind[] values = OutputKind.values();
            return kindOrdinal >= 0 && kindOrdinal < values.length
                    ? values[kindOrdinal] : OutputKind.PRIMARY;
        }
    }

    public record SourceView(boolean initial, int producerNodeId, int producerPortIndex) {
        static SourceView fromInitial() { return new SourceView(true, -1, -1); }
        static SourceView fromProducer(int nodeId, int portIndex) {
            return new SourceView(false, nodeId, portIndex);
        }
    }

    public record EdgeView(int consumerNodeId, int consumerPortIndex, SourceView source,
                           ItemStack material, long quantity) {
        public EdgeView { material = material.copyWithCount(1); }
    }

    public record RootView(ItemStack display, long quantity, long unresolvedQuantity,
                           List<RootEdgeView> allocations, int roleOrdinal) {
        public RootView(ItemStack display, long quantity, long unresolvedQuantity,
                        List<RootEdgeView> allocations) {
            this(display, quantity, unresolvedQuantity, allocations, DemandRole.CONSUMED.ordinal());
        }

        public RootView {
            display = display.copyWithCount(1);
            allocations = List.copyOf(allocations);
        }

        public DemandRole role() {
            DemandRole[] roles = DemandRole.values();
            return roleOrdinal >= 0 && roleOrdinal < roles.length
                    ? roles[roleOrdinal] : DemandRole.CONSUMED;
        }
    }

    public record RootEdgeView(SourceView source, ItemStack material, long quantity) {
        public RootEdgeView { material = material.copyWithCount(1); }
    }

    public record UnresolvedView(int consumerNodeId, int consumerPortIndex,
                                 ItemStack display, long quantity) {
        public UnresolvedView { display = display.copyWithCount(1); }
    }
}
