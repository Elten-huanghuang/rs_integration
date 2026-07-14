package com.huanghuang.rsintegration.crafting.graph;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Server-thread tick loop for bounded concurrent DAG node execution.
 * Every running node is observed before any completion changes the ready set.
 */
public final class ConcurrentNodeExecutor {

    public enum Observation {
        WORKING,
        SUCCEEDED,
        FAILED
    }

    public interface Worker {
        Observation observe();

        default void stopDispatch() {}

        default void cleanupFailure() {}
    }

    @FunctionalInterface
    public interface WorkerFactory {
        Worker start(NodeId nodeId);
    }

    /**
     * Tells the executor whether a node must run exclusively (no other node may
     * be running alongside it). A node is exclusive when its delegate has not
     * proven it is safe to overlap — the conservative default. This is the
     * enforcement behind {@code IBatchDelegate.supportsConcurrentNodeExecution()}:
     * unopted delegates degrade to serial execution instead of silently
     * overlapping physical crafts.
     */
    @FunctionalInterface
    public interface ExclusivityOracle {
        boolean isExclusive(NodeId nodeId);
    }

    private final DagScheduler scheduler;
    private final WorkerFactory workers;
    private final ExclusivityOracle exclusivity;
    private final int maxConcurrentNodes;
    private final Map<NodeId, Worker> running = new LinkedHashMap<>();

    /** Legacy constructor: every node is treated as concurrency-safe. */
    public ConcurrentNodeExecutor(DagScheduler scheduler, WorkerFactory workers,
                                  int maxConcurrentNodes) {
        this(scheduler, workers, maxConcurrentNodes, nodeId -> false);
    }

    public ConcurrentNodeExecutor(DagScheduler scheduler, WorkerFactory workers,
                                  int maxConcurrentNodes, ExclusivityOracle exclusivity) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.workers = Objects.requireNonNull(workers, "workers");
        this.exclusivity = Objects.requireNonNull(exclusivity, "exclusivity");
        if (maxConcurrentNodes < 1) {
            throw new IllegalArgumentException("maxConcurrentNodes must be positive");
        }
        this.maxConcurrentNodes = maxConcurrentNodes;
    }

    public void tick() {
        List<Result> observations = observeAll();
        boolean failed = observations.stream().anyMatch(result -> result.observation == Observation.FAILED);
        if (failed && !scheduler.isStopping()) {
            for (Worker worker : running.values()) worker.stopDispatch();
        }

        for (Result result : observations) {
            switch (result.observation) {
                case WORKING -> { }
                case SUCCEEDED -> {
                    running.remove(result.nodeId);
                    scheduler.succeed(result.nodeId);
                }
                case FAILED -> {
                    running.remove(result.nodeId);
                    result.worker.cleanupFailure();
                    if (!scheduler.isStopping()) {
                        scheduler.fail(result.nodeId);
                    } else {
                        scheduler.failRunningDuringStop(result.nodeId);
                    }
                }
            }
        }

        if (!scheduler.isStopping()) dispatchAvailable();
    }

    public void stopScheduling() {
        scheduler.stopScheduling();
        for (Worker worker : running.values()) worker.stopDispatch();
    }

    public boolean isTerminal() {
        return scheduler.allSucceeded() || (scheduler.isStopping() && running.isEmpty());
    }

    public int runningCount() {
        return running.size();
    }

    private List<Result> observeAll() {
        List<Result> results = new ArrayList<>(running.size());
        for (Map.Entry<NodeId, Worker> entry : List.copyOf(running.entrySet())) {
            Observation observation;
            try {
                observation = Objects.requireNonNull(entry.getValue().observe(), "worker observation");
            } catch (RuntimeException exception) {
                observation = Observation.FAILED;
            }
            results.add(new Result(entry.getKey(), entry.getValue(), observation));
        }
        return results;
    }

    private void dispatchAvailable() {
        // An exclusive node already running blocks all further dispatch.
        if (running.keySet().stream().anyMatch(exclusivity::isExclusive)) return;

        int capacity = maxConcurrentNodes - running.size();
        if (capacity <= 0) return;

        for (NodeId nodeId : scheduler.peekReady(capacity)) {
            boolean nodeExclusive = exclusivity.isExclusive(nodeId);
            // An exclusive node may only start on an empty field; a non-exclusive
            // node may not start once an exclusive one is running. Either way, stop
            // here so the exclusive node runs alone.
            if (nodeExclusive && !running.isEmpty()) return;

            scheduler.claim(nodeId);
            Worker worker;
            try {
                worker = workers.start(nodeId);
            } catch (RuntimeException exception) {
                worker = null;
            }
            if (worker == null) {
                if (scheduler.state(nodeId) == DagScheduler.NodeState.RUNNING) {
                    scheduler.releaseClaim(nodeId);
                }
                // else: factory already transitioned the node (e.g. Earth Heart synchronous)
            } else {
                running.put(nodeId, worker);
            }

            // A freshly started exclusive node must run alone — stop dispatching.
            if (nodeExclusive) return;
        }
    }

    private record Result(NodeId nodeId, Worker worker, Observation observation) {}
}
