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

    public enum StartStatus {
        STARTED,
        RETRY,
        COMPLETED,
        FAILED
    }

    public enum CompletionStatus {
        SUCCEEDED,
        FAILED
    }

    @FunctionalInterface
    public interface CompletionHandler {
        CompletionStatus complete(NodeId nodeId, Worker worker);
    }

    public record StartResult(StartStatus status, Worker worker) {
        public StartResult {
            Objects.requireNonNull(status, "status");
            if ((status == StartStatus.STARTED) != (worker != null)) {
                throw new IllegalArgumentException("only STARTED may carry a worker");
            }
        }

        public static StartResult started(Worker worker) {
            return new StartResult(StartStatus.STARTED, Objects.requireNonNull(worker, "worker"));
        }

        public static StartResult retry() { return new StartResult(StartStatus.RETRY, null); }
        public static StartResult completed() { return new StartResult(StartStatus.COMPLETED, null); }
        public static StartResult failed() { return new StartResult(StartStatus.FAILED, null); }
    }

    @FunctionalInterface
    public interface AdmissionWorkerFactory {
        StartResult start(NodeId nodeId);
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
    private final AdmissionWorkerFactory workers;
    private final CompletionHandler completions;
    private final ExclusivityOracle exclusivity;
    private final int maxConcurrentNodes;
    private final int maxDispatchPerTick;
    private final int maxDispatchPerCraft;
    private int dispatchedThisCraft;
    private final Map<NodeId, RunningWorker> running = new LinkedHashMap<>();

    /** Legacy constructor: every node is treated as concurrency-safe. */
    public ConcurrentNodeExecutor(DagScheduler scheduler, WorkerFactory workers,
                                  int maxConcurrentNodes) {
        this(scheduler, adapt(workers), maxConcurrentNodes, nodeId -> false,
                (nodeId, worker) -> CompletionStatus.SUCCEEDED,
                maxConcurrentNodes, Integer.MAX_VALUE);
    }

    public ConcurrentNodeExecutor(DagScheduler scheduler, WorkerFactory workers,
                                  int maxConcurrentNodes, ExclusivityOracle exclusivity) {
        this(scheduler, adapt(workers), maxConcurrentNodes, exclusivity,
                (nodeId, worker) -> CompletionStatus.SUCCEEDED,
                maxConcurrentNodes, Integer.MAX_VALUE);
    }

    public ConcurrentNodeExecutor(DagScheduler scheduler, AdmissionWorkerFactory workers,
                                  int maxConcurrentNodes, ExclusivityOracle exclusivity) {
        this(scheduler, workers, maxConcurrentNodes, exclusivity,
                (nodeId, worker) -> CompletionStatus.SUCCEEDED,
                maxConcurrentNodes, Integer.MAX_VALUE);
    }

    public ConcurrentNodeExecutor(DagScheduler scheduler, AdmissionWorkerFactory workers,
                                  int maxConcurrentNodes, ExclusivityOracle exclusivity,
                                  CompletionHandler completions) {
        this(scheduler, workers, maxConcurrentNodes, exclusivity, completions,
                maxConcurrentNodes, Integer.MAX_VALUE);
    }

    public ConcurrentNodeExecutor(DagScheduler scheduler, AdmissionWorkerFactory workers,
                                  int maxConcurrentNodes, ExclusivityOracle exclusivity,
                                  CompletionHandler completions, int maxDispatchPerTick,
                                  int maxDispatchPerCraft) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.workers = Objects.requireNonNull(workers, "workers");
        this.completions = Objects.requireNonNull(completions, "completions");
        this.exclusivity = Objects.requireNonNull(exclusivity, "exclusivity");
        if (maxConcurrentNodes < 1 || maxDispatchPerTick < 1 || maxDispatchPerCraft < 1) {
            throw new IllegalArgumentException("executor limits must be positive");
        }
        this.maxConcurrentNodes = maxConcurrentNodes;
        this.maxDispatchPerTick = maxDispatchPerTick;
        this.maxDispatchPerCraft = maxDispatchPerCraft;
    }

    public void tick() {
        int tickBudget = maxDispatchPerTick;
        List<Result> observations = observeAll();
        boolean failed = observations.stream().anyMatch(result -> result.observation == Observation.FAILED);
        if (failed && !scheduler.isStopping()) {
            for (RunningWorker runningWorker : running.values()) runningWorker.worker().stopDispatch();
        }

        for (Result result : observations) {
            switch (result.observation) {
                case WORKING -> { }
                case SUCCEEDED -> {
                    running.remove(result.nodeId);
                    CompletionStatus completion;
                    try {
                        completion = Objects.requireNonNull(
                                completions.complete(result.nodeId, result.worker), "completion status");
                    } catch (RuntimeException exception) {
                        completion = CompletionStatus.FAILED;
                    }
                    if (completion == CompletionStatus.SUCCEEDED
                            && result.epoch == scheduler.epoch() && !scheduler.isStopping()) {
                        scheduler.succeed(result.nodeId);
                    } else if (completion == CompletionStatus.SUCCEEDED && scheduler.isStopping()) {
                        // Stale/in-flight completions may settle resources in the handler,
                        // but cannot unlock dependents after the epoch advances.
                        scheduler.cancelRunningDuringStop(result.nodeId);
                    } else {
                        result.worker.cleanupFailure();
                        if (!scheduler.isStopping()) scheduler.fail(result.nodeId);
                        else scheduler.failRunningDuringStop(result.nodeId);
                    }
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

        if (!scheduler.isStopping()) dispatchAvailable(maxDispatchPerTick);
    }

    public void stopScheduling() {
        scheduler.stopScheduling();
        for (RunningWorker runningWorker : running.values()) runningWorker.worker().stopDispatch();
    }

    public boolean isTerminal() {
        return scheduler.allSucceeded() || (scheduler.isStopping() && running.isEmpty());
    }

    public int runningCount() {
        return running.size();
    }

    private static AdmissionWorkerFactory adapt(WorkerFactory factory) {
        Objects.requireNonNull(factory, "factory");
        return nodeId -> {
            Worker worker = factory.start(nodeId);
            return worker == null ? StartResult.completed() : StartResult.started(worker);
        };
    }

    private List<Result> observeAll() {
        List<Result> results = new ArrayList<>(running.size());
        for (Map.Entry<NodeId, RunningWorker> entry : List.copyOf(running.entrySet())) {
            Observation observation;
            try {
                observation = Objects.requireNonNull(entry.getValue().worker().observe(), "worker observation");
            } catch (RuntimeException exception) {
                observation = Observation.FAILED;
            }
            results.add(new Result(entry.getKey(), entry.getValue().worker(),
                    entry.getValue().epoch(), observation));
        }
        return results;
    }

    private void dispatchAvailable(int tickBudget) {
        // An exclusive node already running blocks all further dispatch.
        if (running.keySet().stream().anyMatch(exclusivity::isExclusive)) return;

        int remainingCraftBudget = maxDispatchPerCraft - dispatchedThisCraft;
        int capacity = Math.min(maxConcurrentNodes - running.size(),
                Math.min(tickBudget, remainingCraftBudget));
        if (capacity <= 0) return;

        for (NodeId nodeId : scheduler.peekReady(capacity)) {
            boolean nodeExclusive = exclusivity.isExclusive(nodeId);
            // An exclusive node may only start on an empty field; a non-exclusive
            // node may not start once an exclusive one is running. Either way, stop
            // here so the exclusive node runs alone.
            if (nodeExclusive && !running.isEmpty()) return;

            scheduler.claim(nodeId);
            StartResult start;
            try {
                start = Objects.requireNonNull(workers.start(nodeId), "worker start result");
            } catch (RuntimeException exception) {
                start = StartResult.failed();
            }
            switch (start.status()) {
                case STARTED -> {
                    running.put(nodeId, new RunningWorker(start.worker(), scheduler.epoch()));
                    dispatchedThisCraft++;
                }
                case RETRY -> scheduler.releaseClaim(nodeId);
                case COMPLETED -> {
                    dispatchedThisCraft++;
                    if (scheduler.state(nodeId) == DagScheduler.NodeState.RUNNING) {
                        scheduler.succeed(nodeId);
                    }
                }
                case FAILED -> {
                    if (scheduler.state(nodeId) == DagScheduler.NodeState.RUNNING) {
                        scheduler.fail(nodeId);
                    }
                }
            }

            if (start.status() == StartStatus.RETRY) continue;
            if (scheduler.isStopping()) return;

            // A freshly started exclusive node must run alone — stop dispatching.
            if (nodeExclusive) return;
        }
    }

    private record RunningWorker(Worker worker, int epoch) {}
    private record Result(NodeId nodeId, Worker worker, int epoch, Observation observation) {}
}
