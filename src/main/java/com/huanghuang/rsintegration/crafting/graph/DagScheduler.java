package com.huanghuang.rsintegration.crafting.graph;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Deterministic graph lifecycle state used by the server-thread executor. */
public final class DagScheduler {

    public enum NodeState {
        BLOCKED,
        READY,
        RUNNING,
        SUCCEEDED,
        FAILED,
        CANCELLED
    }

    private final CraftPlanGraph graph;
    private final Map<NodeId, NodeState> states = new HashMap<>();
    private final Map<NodeId, Set<NodeId>> dependencies = new HashMap<>();
    private final Map<NodeId, Set<NodeId>> dependents = new HashMap<>();
    private final LinkedHashSet<NodeId> ready = new LinkedHashSet<>();
    private boolean stopping;
    private NodeId failedNode;

    public DagScheduler(CraftPlanGraph graph) {
        this.graph = Objects.requireNonNull(graph, "graph");
        CraftPlanValidator.validate(graph);
        for (NodeId nodeId : graph.topologicalOrder()) {
            Set<NodeId> nodeDependencies = new HashSet<>(graph.dependenciesOf(nodeId));
            dependencies.put(nodeId, nodeDependencies);
            states.put(nodeId, nodeDependencies.isEmpty() ? NodeState.READY : NodeState.BLOCKED);
            if (nodeDependencies.isEmpty()) ready.add(nodeId);
            for (NodeId dependency : nodeDependencies) {
                dependents.computeIfAbsent(dependency, ignored -> new LinkedHashSet<>()).add(nodeId);
            }
        }
    }

    public List<NodeId> claimReady(int limit) {
        if (stopping || limit <= 0) return List.of();
        List<NodeId> claimed = new ArrayList<>(Math.min(limit, ready.size()));
        var iterator = ready.iterator();
        while (iterator.hasNext() && claimed.size() < limit) {
            NodeId nodeId = iterator.next();
            iterator.remove();
            requireState(nodeId, NodeState.READY);
            states.put(nodeId, NodeState.RUNNING);
            claimed.add(nodeId);
        }
        return List.copyOf(claimed);
    }

    public void releaseClaim(NodeId nodeId) {
        requireState(nodeId, NodeState.RUNNING);
        if (stopping) {
            states.put(nodeId, NodeState.CANCELLED);
            return;
        }
        states.put(nodeId, NodeState.READY);
        ready.add(nodeId);
    }

    public void succeed(NodeId nodeId) {
        requireState(nodeId, NodeState.RUNNING);
        states.put(nodeId, NodeState.SUCCEEDED);
        if (stopping) return;
        for (NodeId dependent : dependents.getOrDefault(nodeId, Set.of())) {
            if (states.get(dependent) != NodeState.BLOCKED) continue;
            boolean allSucceeded = dependencies.getOrDefault(dependent, Set.of()).stream()
                    .allMatch(dependency -> states.get(dependency) == NodeState.SUCCEEDED);
            if (allSucceeded) {
                states.put(dependent, NodeState.READY);
                ready.add(dependent);
            }
        }
    }

    public void fail(NodeId nodeId) {
        requireState(nodeId, NodeState.RUNNING);
        states.put(nodeId, NodeState.FAILED);
        failedNode = nodeId;
        stopScheduling();
    }

    void failRunningDuringStop(NodeId nodeId) {
        requireState(nodeId, NodeState.RUNNING);
        states.put(nodeId, NodeState.FAILED);
        if (failedNode == null) failedNode = nodeId;
    }

    public void stopScheduling() {
        if (stopping) return;
        stopping = true;
        ready.clear();
        for (Map.Entry<NodeId, NodeState> entry : states.entrySet()) {
            if (entry.getValue() == NodeState.READY || entry.getValue() == NodeState.BLOCKED) {
                entry.setValue(NodeState.CANCELLED);
            }
        }
    }

    public NodeState state(NodeId nodeId) {
        return states.get(nodeId);
    }

    public boolean isStopping() {
        return stopping;
    }

    public NodeId failedNode() {
        return failedNode;
    }

    public boolean allSucceeded() {
        return !states.isEmpty() && states.values().stream().allMatch(state -> state == NodeState.SUCCEEDED);
    }

    public boolean isDrained() {
        return states.values().stream().noneMatch(state -> state == NodeState.RUNNING);
    }

    public Collection<NodeId> runningNodes() {
        return states.entrySet().stream()
                .filter(entry -> entry.getValue() == NodeState.RUNNING)
                .map(Map.Entry::getKey)
                .toList();
    }

    public int readyCount() {
        return ready.size();
    }

    private void requireState(NodeId nodeId, NodeState expected) {
        NodeState actual = states.get(nodeId);
        if (actual == null) throw new IllegalArgumentException("unknown node " + nodeId);
        if (actual != expected) {
            throw new IllegalStateException("node " + nodeId + " is " + actual + ", expected " + expected);
        }
    }
}
