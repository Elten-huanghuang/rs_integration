package com.huanghuang.rsintegration.crafting.graph;

import com.huanghuang.rsintegration.RSIntegrationMod;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

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

    @FunctionalInterface
    public interface PublicationHandler {
        void publish(NodeId nodeId, Worker worker);
    }

    @FunctionalInterface
    public interface FailureHandler {
        void failed(NodeId nodeId, Worker worker);
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
    private final PublicationHandler publications;
    private final CompletionHandler completions;
    private final FailureHandler failures;
    private final ExclusivityOracle exclusivity;
    private final Predicate<NodeId> dispatchable;
    private final int maxConcurrentNodes;
    private final int maxDispatchPerTick;
    private final int maxDispatchPerCraft;
    private int dispatchedThisCraft;
    private final Map<NodeId, RunningWorker> running = new LinkedHashMap<>();
    private final Map<NodeId, Boolean> exclusiveNodes = new LinkedHashMap<>();

    /** Legacy constructor: every node is treated as concurrency-safe. */
    public ConcurrentNodeExecutor(DagScheduler scheduler, WorkerFactory workers,
                                  int maxConcurrentNodes) {
        this(scheduler, adapt(workers), maxConcurrentNodes, nodeId -> false,
                (nodeId, worker) -> { },
                (nodeId, worker) -> CompletionStatus.SUCCEEDED,
                (nodeId, worker) -> { },
                maxConcurrentNodes, Integer.MAX_VALUE, nodeId -> true);
    }

    public ConcurrentNodeExecutor(DagScheduler scheduler, WorkerFactory workers,
                                  int maxConcurrentNodes, ExclusivityOracle exclusivity) {
        this(scheduler, adapt(workers), maxConcurrentNodes, exclusivity,
                (nodeId, worker) -> { },
                (nodeId, worker) -> CompletionStatus.SUCCEEDED,
                (nodeId, worker) -> { },
                maxConcurrentNodes, Integer.MAX_VALUE, nodeId -> true);
    }

    public ConcurrentNodeExecutor(DagScheduler scheduler, AdmissionWorkerFactory workers,
                                  int maxConcurrentNodes, ExclusivityOracle exclusivity) {
        this(scheduler, workers, maxConcurrentNodes, exclusivity,
                (nodeId, worker) -> { },
                (nodeId, worker) -> CompletionStatus.SUCCEEDED,
                (nodeId, worker) -> { },
                maxConcurrentNodes, Integer.MAX_VALUE, nodeId -> true);
    }

    public ConcurrentNodeExecutor(DagScheduler scheduler, AdmissionWorkerFactory workers,
                                  int maxConcurrentNodes, ExclusivityOracle exclusivity,
                                  CompletionHandler completions) {
        this(scheduler, workers, maxConcurrentNodes, exclusivity,
                (nodeId, worker) -> { }, completions,
                (nodeId, worker) -> { },
                maxConcurrentNodes, Integer.MAX_VALUE, nodeId -> true);
    }

    public ConcurrentNodeExecutor(DagScheduler scheduler, AdmissionWorkerFactory workers,
                                  int maxConcurrentNodes, ExclusivityOracle exclusivity,
                                  CompletionHandler completions, int maxDispatchPerTick,
                                  int maxDispatchPerCraft) {
        this(scheduler, workers, maxConcurrentNodes, exclusivity,
                (nodeId, worker) -> { }, completions, maxDispatchPerTick, maxDispatchPerCraft);
    }

    public ConcurrentNodeExecutor(DagScheduler scheduler, AdmissionWorkerFactory workers,
                                  int maxConcurrentNodes, ExclusivityOracle exclusivity,
                                  PublicationHandler publications,
                                  CompletionHandler completions, int maxDispatchPerTick,
                                  int maxDispatchPerCraft) {
        this(scheduler, workers, maxConcurrentNodes, exclusivity, publications,
                completions, (nodeId, worker) -> { }, maxDispatchPerTick, maxDispatchPerCraft,
                nodeId -> true);
    }

    public ConcurrentNodeExecutor(DagScheduler scheduler, AdmissionWorkerFactory workers,
                                  int maxConcurrentNodes, ExclusivityOracle exclusivity,
                                  PublicationHandler publications,
                                  CompletionHandler completions, FailureHandler failures,
                                  int maxDispatchPerTick, int maxDispatchPerCraft) {
        this(scheduler, workers, maxConcurrentNodes, exclusivity, publications, completions,
                failures, maxDispatchPerTick, maxDispatchPerCraft, nodeId -> true);
    }

    public ConcurrentNodeExecutor(DagScheduler scheduler, AdmissionWorkerFactory workers,
                                  int maxConcurrentNodes, ExclusivityOracle exclusivity,
                                  PublicationHandler publications,
                                  CompletionHandler completions, FailureHandler failures,
                                  int maxDispatchPerTick, int maxDispatchPerCraft,
                                  Predicate<NodeId> dispatchable) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.workers = Objects.requireNonNull(workers, "workers");
        this.publications = Objects.requireNonNull(publications, "publications");
        this.completions = Objects.requireNonNull(completions, "completions");
        this.failures = Objects.requireNonNull(failures, "failures");
        this.exclusivity = Objects.requireNonNull(exclusivity, "exclusivity");
        this.dispatchable = Objects.requireNonNull(dispatchable, "dispatchable");
        if (maxConcurrentNodes < 1 || maxDispatchPerTick < 1 || maxDispatchPerCraft < 1) {
            throw new IllegalArgumentException("executor limits must be positive");
        }
        this.maxConcurrentNodes = maxConcurrentNodes;
        this.maxDispatchPerTick = maxDispatchPerTick;
        this.maxDispatchPerCraft = maxDispatchPerCraft;
    }

    public void tick() {
        processTick(true);
    }

    /** Observe, publish and settle once without permitting any new dispatch. */
    public void quiesceOnce() {
        stopScheduling();
        processTick(false);
    }

    private void processTick(boolean allowDispatch) {
        List<Result> observations = observeAll();
        boolean failed = observations.stream().anyMatch(result -> result.observation == Observation.FAILED);
        if (failed && !scheduler.isStopping()) {
            for (RunningWorker runningWorker : running.values()) runningWorker.worker().stopDispatch();
        }

        List<Result> published = new ArrayList<>(observations.size());
        for (Result result : observations) {
            Observation observation = result.observation;
            try {
                publications.publish(result.nodeId, result.worker);
            } catch (RuntimeException exception) {
                RSIntegrationMod.LOGGER.error("Graph node publication failed: {}", result.nodeId, exception);
                observation = Observation.FAILED;
            }
            published.add(new Result(result.nodeId, result.worker, result.epoch, observation));
        }

        for (Result result : published) {
            switch (result.observation) {
                case WORKING -> { }
                case SUCCEEDED -> {
                    running.remove(result.nodeId);
                    CompletionStatus completion;
                    try {
                        completion = Objects.requireNonNull(
                                completions.complete(result.nodeId, result.worker), "completion status");
                    } catch (RuntimeException exception) {
                        RSIntegrationMod.LOGGER.error(
                                "Graph node completion check failed: {}", result.nodeId, exception);
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
                    try { failures.failed(result.nodeId, result.worker); }
                    catch (RuntimeException exception) {
                        RSIntegrationMod.LOGGER.error(
                                "Graph node failure handler threw: {}", result.nodeId, exception);
                    }
                    result.worker.cleanupFailure();
                    if (!scheduler.isStopping()) {
                        scheduler.fail(result.nodeId);
                    } else {
                        scheduler.failRunningDuringStop(result.nodeId);
                    }
                }
            }
        }

        if (allowDispatch && !scheduler.isStopping()) dispatchAvailable(maxDispatchPerTick);
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
                RSIntegrationMod.LOGGER.error(
                        "Graph node observation failed: {}", entry.getKey(), exception);
                observation = Observation.FAILED;
            }
            results.add(new Result(entry.getKey(), entry.getValue().worker(),
                    entry.getValue().epoch(), observation));
        }
        return results;
    }

    private void dispatchAvailable(int tickBudget) {
        // An exclusive node already running blocks all further dispatch.
        if (running.keySet().stream().anyMatch(this::isExclusive)) return;

        int remainingCraftBudget = maxDispatchPerCraft - dispatchedThisCraft;
        int dispatchLimit = Math.min(tickBudget, remainingCraftBudget);
        if (dispatchLimit <= 0 || running.size() >= maxConcurrentNodes) return;

        // Inspect the full ready set. Looking at only `capacity` entries lets an
        // exclusive node at the front hide later concurrency-safe work.
        List<NodeId> ready = scheduler.peekReady(scheduler.readyCount());
        int dispatchedNow = 0;
        for (NodeId nodeId : ready) {
            if (running.size() >= maxConcurrentNodes || dispatchedNow >= dispatchLimit) break;
            if (!dispatchable.test(nodeId)) continue;
            if (isExclusive(nodeId)) continue;

            StartStatus status = dispatch(nodeId);
            if (status == StartStatus.STARTED || status == StartStatus.COMPLETED) {
                dispatchedNow++;
            }
            if (status == StartStatus.FAILED || scheduler.isStopping()) return;
        }

        // Exclusive work may start only when no safe worker was admitted. If all
        // safe candidates asked to retry, try exclusive candidates in stable order
        // so one unavailable machine cannot stall otherwise runnable work.
        if (!running.isEmpty() || dispatchedNow > 0 || dispatchedNow >= dispatchLimit) return;
        for (NodeId nodeId : ready) {
            if (!dispatchable.test(nodeId)) continue;
            if (!isExclusive(nodeId)) continue;
            StartStatus status = dispatch(nodeId);
            if (status != StartStatus.RETRY) return;
        }
    }

    private boolean isExclusive(NodeId nodeId) {
        return exclusiveNodes.computeIfAbsent(nodeId, exclusivity::isExclusive);
    }

    private StartStatus dispatch(NodeId nodeId) {
        scheduler.claim(nodeId);
        StartResult start;
        try {
            start = Objects.requireNonNull(workers.start(nodeId), "worker start result");
        } catch (RuntimeException exception) {
            RSIntegrationMod.LOGGER.error("Graph node start failed: {}", nodeId, exception);
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
        return start.status();
    }

    private record RunningWorker(Worker worker, int epoch) {}
    private record Result(NodeId nodeId, Worker worker, int epoch, Observation observation) {}
}
