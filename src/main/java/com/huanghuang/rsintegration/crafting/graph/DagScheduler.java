package com.huanghuang.rsintegration.crafting.graph;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

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

    private final List<NodeId> order = new ArrayList<>();
    private final Map<NodeId, Set<NodeId>> retryDependencies = new HashMap<>();
    private final Consumer<NodeId> nodeBecameReady;
    private final Map<NodeId, NodeState> states = new HashMap<>();
    private final Map<NodeId, Set<NodeId>> dependencies = new HashMap<>();
    private final Map<NodeId, Set<NodeId>> dependents = new HashMap<>();
    private final LinkedHashSet<NodeId> ready = new LinkedHashSet<>();
    private long succeededCount;
    private boolean stopping;
    private int epoch;

    public int epoch() { return epoch; }
    private NodeId failedNode;

    public long countSucceeded() {
        return succeededCount;
    }

    public DagScheduler(CraftPlanGraph graph) {
        this(graph, ignored -> {});
    }

    /** 节点首次从 BLOCKED 转为 READY 时调用监听器。 */
    public DagScheduler(CraftPlanGraph graph, Consumer<NodeId> nodeBecameReady) {
        this.nodeBecameReady = Objects.requireNonNull(nodeBecameReady, "nodeBecameReady");
        CraftPlanValidator.validate(graph);
        register(graph);
    }

    /** 追加节点享有与初始节点相同的调度和停服语义；既有节点的位置和在途操作不变。 */
    public void appendForRetry(NodeId consumer, CraftPlanGraph graph) {
        requireState(consumer, NodeState.RUNNING);
        if (stopping) throw new IllegalStateException("停止后不能追加补料");
        CraftPlanValidator.validate(graph);
        if (graph.topologicalOrder().stream().anyMatch(states::containsKey)) {
            throw new IllegalArgumentException("补料节点号与任务冲突");
        }
        Set<NodeId> producers = new LinkedHashSet<>();
        for (RootDemand root : graph.rootDemands()) {
            for (RootAllocation allocation : root.allocations()) {
                if (allocation.source() instanceof MaterialSource.ProducerOutput source) {
                    producers.add(source.outputPort().nodeId());
                }
            }
        }
        register(graph);
        if (!producers.isEmpty()) {
            retryDependencies.put(consumer, producers);
            for (NodeId producer : producers) {
                dependents.computeIfAbsent(producer, ignored -> new LinkedHashSet<>()).add(consumer);
            }
        }
    }

    public List<NodeId> nodeOrder() { return Collections.unmodifiableList(order); }

    private void register(CraftPlanGraph graph) {
        order.addAll(graph.topologicalOrder());
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

    /**
     * Return up to {@code limit} ready nodes in claim order WITHOUT transitioning
     * them. Lets a caller inspect candidates (e.g. for an exclusivity decision)
     * before committing to {@link #claim(NodeId)}.
     */
    public List<NodeId> peekReady(int limit) {
        if (stopping || limit <= 0) return List.of();
        List<NodeId> peeked = new ArrayList<>(Math.min(limit, ready.size()));
        var iterator = ready.iterator();
        while (iterator.hasNext() && peeked.size() < limit) {
            peeked.add(iterator.next());
        }
        return List.copyOf(peeked);
    }

    /** 未探测的候选轮转到队尾，避免有界扫描一直停在同一批节点上。 */
    public void deferReady(NodeId nodeId) {
        if (ready.remove(nodeId)) ready.add(nodeId);
    }

    /** Claim one specific ready node, transitioning it READY → RUNNING. */
    public void claim(NodeId nodeId) {
        if (stopping) throw new IllegalStateException("cannot claim while stopping: " + nodeId);
        requireState(nodeId, NodeState.READY);
        ready.remove(nodeId);
        states.put(nodeId, NodeState.RUNNING);
    }

    /** Claim the first ready node accepted by {@code predicate}, preserving ready order. */
    public NodeId claimFirstReady(Predicate<NodeId> predicate) {
        Objects.requireNonNull(predicate, "predicate");
        if (stopping) return null;
        for (NodeId nodeId : ready) {
            if (!predicate.test(nodeId)) continue;
            claim(nodeId);
            return nodeId;
        }
        return null;
    }

    public void releaseClaim(NodeId nodeId) {
        requireState(nodeId, NodeState.RUNNING);
        if (stopping) {
            states.put(nodeId, NodeState.CANCELLED);
            return;
        }
        if (!retryDependenciesMet(nodeId)) {
            states.put(nodeId, NodeState.BLOCKED);
            return;
        }
        states.put(nodeId, NodeState.READY);
        ready.add(nodeId);
    }

    public void succeed(NodeId nodeId) {
        succeed(nodeId, ignored -> true);
    }

    /** Complete a node and unlock only dependents whose material gate is ready. */
    public void succeed(NodeId nodeId, Predicate<NodeId> readiness) {
        Objects.requireNonNull(readiness, "readiness");
        requireState(nodeId, NodeState.RUNNING);
        states.put(nodeId, NodeState.SUCCEEDED);
        succeededCount++;
        if (stopping) return;
        for (NodeId dependent : dependents.getOrDefault(nodeId, Set.of())) {
            if (states.get(dependent) != NodeState.BLOCKED) continue;
            boolean allSucceeded = dependencies.getOrDefault(dependent, Set.of()).stream()
                    .allMatch(dependency -> states.get(dependency) == NodeState.SUCCEEDED);
            if (retryDependenciesMet(dependent) && allSucceeded && readiness.test(dependent)) {
                markReady(dependent);
            }
        }
    }

    /** Re-evaluate material-gated nodes after incremental publications. */
    public void refreshBlocked(Predicate<NodeId> readiness) {
        Objects.requireNonNull(readiness, "readiness");
        if (stopping) return;
        for (NodeId nodeId : order) {
            refreshBlockedNode(nodeId, readiness);
        }
    }

    /** 候选位对应拓扑位置，确保多个来源的通知去重后仍沿用原就绪顺序。 */
    public void refreshBlocked(BitSet candidates, Predicate<NodeId> readiness) {
        Objects.requireNonNull(candidates, "candidates");
        Objects.requireNonNull(readiness, "readiness");
        if (stopping) return;
        if (candidates.length() > order.size()) {
            throw new IllegalArgumentException("candidate position exceeds graph order");
        }
        for (int index = candidates.nextSetBit(0); index >= 0;
             index = candidates.nextSetBit(index + 1)) {
            refreshBlockedNode(order.get(index), readiness);
        }
    }

    private void refreshBlockedNode(NodeId nodeId, Predicate<NodeId> readiness) {
        if (states.get(nodeId) != NodeState.BLOCKED) return;
        if (retryDependenciesMet(nodeId) && readiness.test(nodeId)) {
            markReady(nodeId);
        }
    }

    private void markReady(NodeId nodeId) {
        retryDependencies.remove(nodeId);
        states.put(nodeId, NodeState.READY);
        ready.add(nodeId);
        nodeBecameReady.accept(nodeId);
    }

    private boolean retryDependenciesMet(NodeId nodeId) {
        return retryDependencies.getOrDefault(nodeId, Set.of()).stream()
                .allMatch(id -> states.get(id) == NodeState.SUCCEEDED);
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

    void cancelRunningDuringStop(NodeId nodeId) {
        requireState(nodeId, NodeState.RUNNING);
        states.put(nodeId, NodeState.CANCELLED);
    }

    public void stopScheduling() {
        if (stopping) return;
        stopping = true;
        epoch++;
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

    public Map<NodeId, NodeState> stateSnapshot() {
        return Map.copyOf(states);
    }

    public boolean isStopping() {
        return stopping;
    }

    public NodeId failedNode() {
        return failedNode;
    }

    public boolean allSucceeded() {
        return !states.isEmpty() && succeededCount == states.size();
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
