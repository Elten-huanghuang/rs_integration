package com.huanghuang.rsintegration.crafting.graph;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 补料规划器使用局部节点号；接入任务前一次性重定位所有端口，根分配留给重试节点。 */
public final class SupplementalGraph {
    private final Map<NodeId, NodeId> ids = new HashMap<>();

    private SupplementalGraph() {}

    public static CraftPlanGraph relocate(CraftPlanGraph plan, int firstNode) {
        CraftPlanValidator.validate(plan);
        if (!plan.unresolvedDemands().isEmpty()
                || plan.rootDemands().stream().anyMatch(root -> root.unresolvedQuantity() != 0)) {
            throw new IllegalArgumentException("不能追加未解决的补料图");
        }
        var mapping = new SupplementalGraph();
        for (int i = 0; i < plan.topologicalOrder().size(); i++) {
            mapping.ids.put(plan.topologicalOrder().get(i), new NodeId(Math.addExact(firstNode, i)));
        }
        return new CraftPlanGraph(plan.version(), plan.nodes().stream().map(mapping::node).toList(),
                plan.allocations().stream().map(a -> new MaterialAllocation(a.id(),
                        mapping.input(a.consumer()), mapping.source(a.source()), a.material(), a.quantity())).toList(),
                plan.rootDemands().stream().map(root -> new RootDemand(root.ingredient(), root.quantity(), 0,
                        root.displayHint(), root.allocations().stream().map(a -> new RootAllocation(
                                mapping.source(a.source()), a.material(), a.quantity())).toList(), root.role())).toList(),
                List.of(), plan.topologicalOrder().stream().map(mapping.ids::get).toList(), plan.planningRevision());
    }

    private CraftNode node(CraftNode node) {
        return new CraftNode(ids.get(node.id()), node.recipeId(), node.modTypeId(), node.recipeTypeId(),
                node.executions(), node.alternativeIds(), node.alternativeModTypeIds(), node.inferMode(),
                node.syntheticInput(), node.syntheticOutput(),
                node.inputs().stream().map(i -> new InputDemand(input(i.id()), i.ingredient(),
                        i.quantity(), i.consumption(), i.displayHint())).toList(),
                node.outputs().stream().map(o -> new OutputDeclaration(output(o.id()), o.material(),
                        o.quantity(), o.kind(), o.minimumQuantity(), o.maximumQuantity(), o.probability(),
                        o.dynamicIdentity(), o.sourceInput() == null ? null : input(o.sourceInput()),
                        o.resource(), o.targetProducing())).toList(), node.magicCosts());
    }

    private InputPortId input(InputPortId id) { return new InputPortId(ids.get(id.nodeId()), id.index()); }
    private OutputPortId output(OutputPortId id) { return new OutputPortId(ids.get(id.nodeId()), id.index()); }
    private MaterialSource source(MaterialSource source) {
        return source instanceof MaterialSource.ProducerOutput producer
                ? new MaterialSource.ProducerOutput(output(producer.outputPort())) : source;
    }
}
