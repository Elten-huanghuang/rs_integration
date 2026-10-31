package com.huanghuang.rsintegration.crafting.graph;

import com.huanghuang.rsintegration.crafting.CraftPlanningRevision;
import com.huanghuang.rsintegration.magic.MagicResourceCost;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

public record CraftPlanGraph(
        int version,
        List<CraftNode> nodes,
        List<MaterialAllocation> allocations,
        List<RootDemand> rootDemands,
        List<UnresolvedDemand> unresolvedDemands,
        List<NodeId> topologicalOrder,
        long planningRevision
) {
    public static final int CURRENT_VERSION = 1;

    public CraftPlanGraph(int version, List<CraftNode> nodes,
                          List<MaterialAllocation> allocations,
                          List<RootDemand> rootDemands,
                          List<UnresolvedDemand> unresolvedDemands,
                          List<NodeId> topologicalOrder) {
        this(version, nodes, allocations, rootDemands, unresolvedDemands,
                topologicalOrder, CraftPlanningRevision.current());
    }

    public CraftPlanGraph {
        if (version <= 0) throw new IllegalArgumentException("graph version must be positive");
        if (planningRevision <= 0) throw new IllegalArgumentException("planning revision must be positive");
        nodes = List.copyOf(nodes);
        allocations = List.copyOf(allocations);
        rootDemands = List.copyOf(rootDemands);
        topologicalOrder = List.copyOf(topologicalOrder);
        // 纯投料终端没有可声明的产物，最后一个节点本身即为执行目标。
        NodeId terminalId = topologicalOrder.isEmpty()
                ? null : topologicalOrder.get(topologicalOrder.size() - 1);
        boolean preparationTerminal = terminalId != null && nodes.stream().anyMatch(node ->
                node.id().equals(terminalId) && node.outputs().isEmpty());
        if (rootDemands.isEmpty() && !nodes.isEmpty() && !preparationTerminal) {
            throw new IllegalArgumentException("non-empty graph requires at least one root demand");
        }
        unresolvedDemands = List.copyOf(unresolvedDemands);
    }

    public Map<NodeId, CraftNode> nodesById() {
        return nodes.stream().collect(Collectors.toUnmodifiableMap(CraftNode::id, Function.identity()));
    }

    public Set<NodeId> dependenciesOf(NodeId nodeId) {
        return allocations.stream()
                .filter(allocation -> allocation.consumer().nodeId().equals(nodeId))
                .map(MaterialAllocation::source)
                .filter(MaterialSource.ProducerOutput.class::isInstance)
                .map(MaterialSource.ProducerOutput.class::cast)
                .map(source -> source.outputPort().nodeId())
                .collect(Collectors.toUnmodifiableSet());
    }

    /** 整个递归执行计划按实际节点次数汇总的魔法资源费用。 */
    public List<MagicResourceCost> magicCosts() {
        List<MagicResourceCost> costs = new ArrayList<>();
        for (CraftNode node : nodes) {
            for (MagicResourceCost cost : node.magicCosts()) {
                long total = cost.totalFor(node.executions());
                if (total <= 0L) continue;
                costs.add(new MagicResourceCost(cost.kind(), total));
            }
        }
        return MagicResourceCost.merge(costs);
    }
}
