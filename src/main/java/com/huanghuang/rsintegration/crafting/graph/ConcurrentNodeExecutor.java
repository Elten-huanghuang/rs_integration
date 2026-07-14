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

    private final DagScheduler scheduler;
    private final WorkerFactory workers;
    private final int maxConcurrentNodes;
    private final Map<NodeId, Worker> running = new LinkedHashMap<>();

    public ConcurrentNodeExecutor(DagScheduler scheduler, WorkerFactory workers,
                                  int maxConcurrentNodes) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.workers = Objects.requireNonNull(workers, "workers");
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
        int capacity = maxConcurrentNodes - running.size();
        if (capacity <= 0) return;
        for (NodeId nodeId : scheduler.claimReady(capacity)) {
            Worker worker;
            try {
                worker = workers.start(nodeId);
            } catch (RuntimeException exception) {
                worker = null;
            }
            if (worker == null) {
                scheduler.releaseClaim(nodeId);
            } else {
                running.put(nodeId, worker);
            }
        }
    }

    private record Result(NodeId nodeId, Worker worker, Observation observation) {}
}
