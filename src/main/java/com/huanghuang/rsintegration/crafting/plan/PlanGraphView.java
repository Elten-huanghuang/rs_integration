package com.huanghuang.rsintegration.crafting.plan;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.crafting.graph.CraftNode;
import com.huanghuang.rsintegration.crafting.graph.CraftPlanGraph;
import com.huanghuang.rsintegration.crafting.graph.MaterialSource;
import com.huanghuang.rsintegration.crafting.graph.NodeId;
import com.huanghuang.rsintegration.crafting.graph.OutputKind;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

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
        List<Integer> topologicalOrder
) {
    public PlanGraphView {
        nodes = List.copyOf(nodes);
        edges = List.copyOf(edges);
        roots = List.copyOf(roots);
        unresolved = List.copyOf(unresolved);
        topologicalOrder = List.copyOf(topologicalOrder);
    }

    public static PlanGraphView from(CraftPlanGraph graph) {
        List<NodeView> nodes = new ArrayList<>(graph.nodes().size());
        for (CraftNode node : graph.nodes()) {
            ItemStack primary = node.outputs().stream()
                    .filter(output -> output.kind() == OutputKind.PRIMARY)
                    .findFirst()
                    .map(output -> output.material().toStack(
                            Math.max(1, output.quantity() / Math.max(1, node.executions()))))
                    .orElseGet(() -> node.syntheticOutput() != null
                            ? node.syntheticOutput().copy() : ItemStack.EMPTY);
            List<InputView> inputs = node.inputs().stream()
                    .map(input -> new InputView(input.id().index(), input.displayHint(),
                            input.quantity(), input.role().ordinal()))
                    .toList();
            List<OutputView> outputs = node.outputs().stream()
                    .map(output -> new OutputView(output.id().index(),
                            output.material().toStack(output.quantity()),
                            output.quantity(), output.kind().ordinal()))
                    .toList();
            nodes.add(new NodeView(node.id().value(), node.recipeId(), node.modTypeId(),
                    node.executions(), primary, node.alternativeIds(), node.alternativeModTypeIds(),
                    inputs, outputs));
        }

        List<EdgeView> edges = graph.allocations().stream().map(allocation -> {
            SourceView source = source(allocation.source());
            return new EdgeView(allocation.consumer().nodeId().value(),
                    allocation.consumer().index(), source,
                    allocation.material().toStack(allocation.quantity()), allocation.quantity());
        }).toList();

        List<RootView> roots = graph.rootDemands().stream()
                .map(root -> new RootView(root.displayHint(), root.quantity(), root.unresolvedQuantity(),
                        root.allocations().stream()
                                .map(allocation -> new RootEdgeView(source(allocation.source()),
                                        allocation.material().toStack(allocation.quantity()),
                                        allocation.quantity()))
                                .toList()))
                .toList();

        List<UnresolvedView> unresolved = graph.unresolvedDemands().stream()
                .map(missing -> new UnresolvedView(missing.consumer().nodeId().value(),
                        missing.consumer().index(), missing.displayHint(), missing.quantity()))
                .toList();
        return new PlanGraphView(graph.version(), nodes, edges, roots, unresolved,
                graph.topologicalOrder().stream().map(NodeId::value).toList());
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

    public record NodeView(int nodeId, ResourceLocation recipeId, String modTypeId,
                           int executions, ItemStack primaryOutput,
                           List<ResourceLocation> alternativeIds,
                           List<String> alternativeModTypeIds,
                           List<InputView> inputs, List<OutputView> outputs) {
        public NodeView(int nodeId, ResourceLocation recipeId, String modTypeId,
                        int executions, ItemStack primaryOutput,
                        List<InputView> inputs, List<OutputView> outputs) {
            this(nodeId, recipeId, modTypeId, executions, primaryOutput,
                    List.of(), List.of(), inputs, outputs);
        }

        public NodeView {
            primaryOutput = primaryOutput.copy();
            alternativeIds = List.copyOf(alternativeIds);
            alternativeModTypeIds = List.copyOf(alternativeModTypeIds);
            inputs = List.copyOf(inputs);
            outputs = List.copyOf(outputs);
        }

        public PlanStep asPlanStep() {
            List<ItemStack> displayInputs = inputs.stream()
                    .map(input -> input.display().copyWithCount(input.quantity())).toList();
            return new PlanStep(recipeId, primaryOutput.copy(), Math.max(1, executions),
                    displayInputs, alternativeIds, ModType.byId(modTypeId), 0,
                    !alternativeIds.isEmpty(), 0, 0, alternativeModTypeIds);
        }
    }

    public record InputView(int portIndex, ItemStack display, int quantity, int roleOrdinal) {
        public InputView { display = display.copyWithCount(1); }
    }

    public record OutputView(int portIndex, ItemStack display, int quantity, int kindOrdinal) {
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
                           ItemStack material, int quantity) {
        public EdgeView { material = material.copyWithCount(1); }
    }

    public record RootView(ItemStack display, int quantity, int unresolvedQuantity,
                           List<RootEdgeView> allocations) {
        public RootView {
            display = display.copyWithCount(1);
            allocations = List.copyOf(allocations);
        }
    }

    public record RootEdgeView(SourceView source, ItemStack material, int quantity) {
        public RootEdgeView { material = material.copyWithCount(1); }
    }

    public record UnresolvedView(int consumerNodeId, int consumerPortIndex,
                                 ItemStack display, int quantity) {
        public UnresolvedView { display = display.copyWithCount(1); }
    }
}
