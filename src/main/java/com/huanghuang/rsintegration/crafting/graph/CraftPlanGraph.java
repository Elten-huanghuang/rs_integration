package com.huanghuang.rsintegration.crafting.graph;

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
        List<NodeId> topologicalOrder
) {
    public static final int CURRENT_VERSION = 1;

    public CraftPlanGraph {
        if (version <= 0) throw new IllegalArgumentException("graph version must be positive");
        nodes = List.copyOf(nodes);
        allocations = List.copyOf(allocations);
        rootDemands = List.copyOf(rootDemands);
        if (rootDemands.isEmpty() && !nodes.isEmpty()) {
            throw new IllegalArgumentException("non-empty graph requires at least one root demand");
        }
        unresolvedDemands = List.copyOf(unresolvedDemands);
        topologicalOrder = List.copyOf(topologicalOrder);
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
}
