package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.crafting.batch.ProductionEvidence;
import com.huanghuang.rsintegration.crafting.batch.MachineOperation;
import com.huanghuang.rsintegration.api.execution.CollectedOutput;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.util.PerformanceMonitor;
import com.huanghuang.rsintegration.crafting.batch.IBatchDelegate;
import com.huanghuang.rsintegration.crafting.batch.OperationLifecycle;
import com.huanghuang.rsintegration.crafting.batch.OperationStartContext;
import com.huanghuang.rsintegration.crafting.batch.AbstractBatchDelegate;
import com.huanghuang.rsintegration.crafting.batch.OutputAccounting;
import com.huanghuang.rsintegration.crafting.graph.ConcurrentNodeExecutor;
import com.huanghuang.rsintegration.crafting.graph.MachineLeaseRegistry;
import com.huanghuang.rsintegration.crafting.graph.NodeAdmissionCoordinator;
import com.huanghuang.rsintegration.crafting.graph.NodeId;
import com.huanghuang.rsintegration.crafting.graph.NodeOutputAccumulator;
import com.huanghuang.rsintegration.crafting.graph.OutputPortId;
import com.huanghuang.rsintegration.crafting.loadbalancer.ParallelCraftGroup;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.ItemStack;
import com.huanghuang.rsintegration.RSIntegrationMod;
import java.util.ArrayList;
import net.minecraft.server.level.ServerLevel;
import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.BooleanSupplier;

/**
 * Wraps a single DAG node's physical delegate, ledger, capture handle, and timeout.
 * Exposes {@link ConcurrentNodeExecutor.Worker} so the graph scheduler can observe
 * and drain it alongside other running nodes.
 */
final class CraftNodeRuntime implements ConcurrentNodeExecutor.Worker {

    private final NodeId nodeId;
    private final String nodeLabel;
    private final IBatchDelegate delegate;
    private final OperationLifecycle operation;
    @Nullable
    private final ExtractionLedger nodeLedger;
    @Nullable
    private final MachineLeaseRegistry.Lease machineLease;
    @Nullable
    private final NodeAdmissionCoordinator.Admission admission;
    @Nullable
    private final OperationExecutionKernel.Session operationSession;
    @Nullable
    private CaptureSession capture;
    @Nullable
    private NodeOutputAccumulator outputs;
    @Nullable
    private OutputPortId completionTargetPort;
    private ProbabilisticProduction production;
    private Map<OutputPortId, Long> roundMinimums = Map.of();
    private boolean dispatched;
    private boolean resourcesClosed;
    private List<ItemStack> confirmedOutputs = List.of();
    private boolean terminalOutputsDrained;
    private final Deque<NodeOutputAccumulator.Publication> queuedPublications = new ArrayDeque<>();
    private int waitTicks;
    private long lastObservationTick = Long.MIN_VALUE;
    private int drainingTicks;
    private int observedCompletedOperations;
    private long observedProductionSequence;
    private long observedCapturedCount;
    private long observedCompletedWork;
    /** Logical recipe executions owned by this physical delegate start. */
    private int logicalOperations = 1;
    private boolean progressObserved;
    private boolean stopRequested;
    private String failureReason;
    private CraftFailureCode failureCode = CraftFailureCode.NONE;
    private boolean terminal;
    private boolean reusableMaterialsRecovered;
    private List<ExtractionLedger.ReservationToken> reusableReservationTokens = List.of();
    private boolean reusableReservationsSettled;
    private boolean physicalFailureCleanupCompleted;
    @Nullable
    private List<ItemStack> failureRecoveredInputs;
    private List<ItemStack> virtualInventory;
    @Nullable
    private ServerPlayer player;

    CraftNodeRuntime(NodeId nodeId, String nodeLabel, IBatchDelegate delegate,
                     @Nullable ExtractionLedger nodeLedger,
                     @Nullable MachineLeaseRegistry.Lease machineLease) {
        this(nodeId, nodeLabel, delegate, nodeLedger, machineLease, null, null);
    }

    CraftNodeRuntime(NodeId nodeId, String nodeLabel, IBatchDelegate delegate,
                     @Nullable ExtractionLedger nodeLedger,
                     @Nullable NodeAdmissionCoordinator.Admission admission,
                     @Nullable OperationExecutionKernel.Session operationSession) {
        this(nodeId, nodeLabel, delegate, nodeLedger, null, admission, operationSession);
    }

    private CraftNodeRuntime(NodeId nodeId, String nodeLabel, IBatchDelegate delegate,
                             @Nullable ExtractionLedger nodeLedger,
                             @Nullable MachineLeaseRegistry.Lease machineLease,
                             @Nullable NodeAdmissionCoordinator.Admission admission,
                             @Nullable OperationExecutionKernel.Session operationSession) {
        this.nodeId = nodeId;
        this.nodeLabel = nodeLabel;
        this.delegate = delegate;
        this.operation = delegate == null ? null : new OperationLifecycle(delegate);
        this.nodeLedger = nodeLedger;
        this.machineLease = machineLease;
        this.admission = admission;
        this.operationSession = operationSession;
    }

    boolean start(OperationStartContext context) { return operation.start(context); }

    void attachCapture(CaptureSession handle) {
        this.capture = handle;
    }

    boolean hasCaptureScope() {
        return capture != null
                || (operationSession != null && operationSession.hasCaptureScope());
    }

    void attachOutputs(NodeOutputAccumulator outputs) {
        // Some JEI recipes use an item stack only as a clickable representation
        // of a world mutation. Such a delegate proves success from machine state
        // and must not be forced to manufacture the display-only stack.
        this.outputs = delegate != null && delegate.publishesDeclaredGraphOutputs()
                ? outputs : null;
    }

    void targetCompletion(OutputPortId port, ProbabilisticProduction production, Map<OutputPortId, Long> minimums) {
        completionTargetPort = port;
        this.production = production;
        roundMinimums = Map.copyOf(minimums);
    }

    private final List<CollectedOutput> deliveredReports = new ArrayList<>();

    List<CollectedOutput> drainDeliveredReports() {
        List<CollectedOutput> result = List.copyOf(deliveredReports);
        deliveredReports.clear();
        return result;
    }

    List<NodeOutputAccumulator.Publication> drainIncrementalOutputs() {
        if (outputs == null && production == null) return List.of();
        List<ItemStack> actual = new ArrayList<>(drainSettledResults());
        if (delegate != null) actual.addAll(operation.drainHeld());
        if (!actual.isEmpty()) {
            waitTicks = 0;
            progressObserved = true;
        }
        if (terminal && !terminalOutputsDrained) {
            terminalOutputsDrained = true;
            actual.addAll(confirmedOutputs);
        }
        List<CollectedOutput> resources = new ArrayList<>(
                ProductionEvidence.from(delegate).takeDelivered());
        // 已写回普通网络的报告和仅观察产量都不代表任务私有实物。
        deliveredReports.addAll(resources);
        List<CollectedOutput> observed = ProductionEvidence.from(delegate).takeObserved();
        if (!resources.isEmpty() || !observed.isEmpty()) {
            waitTicks = 0;
            progressObserved = true;
        }
        if (production != null) production.settle(List.of(), resources, observed);
        if (outputs == null) {
            if (virtualInventory != null) virtualInventory.addAll(actual);
            return List.of();
        }
        var published = outputs.add(actual);
        if (production != null) for (var publication : published) {
            if (publication.port().equals(completionTargetPort)) production.published(publication.stack().getCount());
        }
        return published;
    }

    void prepareGraphPublications() {
        if (queuedPublications.isEmpty()) queuedPublications.addAll(drainIncrementalOutputs());
    }

    @Override
    public boolean hasPendingPublications() {
        return !queuedPublications.isEmpty();
    }

    NodeOutputAccumulator.Publication nextGraphPublication() {
        return queuedPublications.getFirst();
    }

    void graphPublicationCompleted() {
        queuedPublications.removeFirst();
    }

    @Override
    public void setWorkAllowed(BooleanSupplier workAllowed) {
        if (delegate instanceof ParallelCraftGroup group) group.setObservationAllowed(workAllowed);
    }

    boolean consumeProgressSignal() {
        boolean observed = progressObserved;
        progressObserved = false;
        return observed;
    }

    boolean outputsComplete() {
        return operationOutputsComplete() && productionTargetComplete();
    }

    /** 概率未命中不属于操作失败，数量目标由订单层继续补做。 */
    boolean operationOutputsComplete() {
        if (outputs == null) return true;
        if (production == null) return outputs.isComplete();
        return roundMinimums.entrySet().stream().allMatch(e -> outputs.publishedQuantity(e.getKey()) >= e.getValue());
    }

    boolean productionTargetComplete() {
        return production == null || production.complete() && (outputs == null || outputs.isComplete());
    }

    List<ItemStack> drainOutputSurplus() {
        return outputs == null ? List.of() : outputs.drainSurplus();
    }

    void setChainContext(List<ItemStack> virtualInventory, @Nullable ServerPlayer player) {
        this.virtualInventory = virtualInventory;
        this.player = player;
    }

    NodeId nodeId() { return nodeId; }

    boolean hasDelegate() { return delegate != null; }

    IBatchDelegate delegate() { return delegate; }

    @Nullable ExtractionLedger nodeLedger() { return nodeLedger; }

    @Nullable NodeAdmissionCoordinator.Admission admission() { return admission; }

    void markDispatched() { dispatched = true; }

    void setLogicalOperations(int operations) {
        logicalOperations = Math.max(1, operations);
    }

    void markStartFailed(String reason) {
        markStartFailed(reason, CraftFailureCode.START_REJECTED);
    }

    void markStartFailed(String reason, CraftFailureCode code) {
        failureReason = reason;
        failureCode = code == null ? CraftFailureCode.UNKNOWN : code;
    }

    void markCompletionFailed(String reason) {
        markCompletionFailed(reason, CraftFailureCode.UNKNOWN);
    }

    void markCompletionFailed(String reason, CraftFailureCode code) {
        failureReason = reason;
        failureCode = code == null ? CraftFailureCode.UNKNOWN : code;
    }

    String outputShortageDetail() {
        if (outputs == null) return "declared graph outputs were not fully collected";
        String detail = outputs.describeShortages();
        return detail.isEmpty() ? "declared graph outputs were not fully collected"
                : "output shortage: " + detail;
    }

    boolean wasDispatched() { return dispatched; }

    @Nullable OperationExecutionKernel.TerminalClass operationTerminalClass() {
        if (operationSession != null) return operationSession.terminalClass();
        if (delegate instanceof ParallelCraftGroup group) {
            OperationExecutionKernel.TerminalClass failure = group.failureTerminalClass();
            if (failure != null) return failure;
            if (group.getRunningOperations() > 0) {
                return OperationExecutionKernel.TerminalClass.IN_FLIGHT;
            }
            if (group.getTotalOperations() > group.getCompletedOperations()) {
                return OperationExecutionKernel.TerminalClass.PRE_START;
            }
            return group.getTotalOperations() > 0
                    ? OperationExecutionKernel.TerminalClass.SETTLED : null;
        }
        return null;
    }

    OperationExecutionKernel.CompletionResult completeOperation(
            OperationExecutionKernel.OutputValidator outputs,
            OperationExecutionKernel.SettlementAction settlement) {
        if (operationSession == null) {
            boolean outputComplete = outputs.validate();
            settlement.settle();
            return outputComplete
                    ? OperationExecutionKernel.CompletionResult.SUCCEEDED
                    : OperationExecutionKernel.CompletionResult.OUTPUT_SHORTAGE;
        }
        return operationSession.complete(outputs, settlement);
    }

    void setReusableReservationTokens(List<ExtractionLedger.ReservationToken> tokens) {
        this.reusableReservationTokens = tokens == null ? List.of() : List.copyOf(tokens);
    }

    boolean physicalFailureCleanupCompleted() {
        return physicalFailureCleanupCompleted;
    }

    @Nullable
    List<ItemStack> failureRecoveredInputs() {
        return failureRecoveredInputs;
    }

    @Nullable
    List<ItemStack> failureRecoveredProducerInputs() {
        return delegate instanceof ParallelCraftGroup group
                ? group.failureRecoveredProducerInputs() : failureRecoveredInputs;
    }

    static boolean shouldRefundInFlightMaterials(
            @Nullable OperationExecutionKernel.TerminalClass terminalClass,
            boolean physicalCleanupCompleted) {
        return physicalCleanupCompleted
                && terminalClass == OperationExecutionKernel.TerminalClass.IN_FLIGHT;
    }

    void settleOperation(OperationExecutionKernel.SettlementAction action) {
        if (operationSession != null && !operationSession.settled()) {
            operationSession.settle(action);
        } else {
            action.settle();
        }
    }

    void classifyTermination(TerminationCoordinator coordinator) {
        if (delegate instanceof ParallelCraftGroup group) {
            int completed = group.getCompletedOperations();
            int running = group.getRunningOperations();
            int queued = Math.max(0, group.getTotalOperations() - completed - running);
            coordinator.classify(TerminationCoordinator.OperationState.SETTLED, completed);
            coordinator.classify(TerminationCoordinator.OperationState.IN_FLIGHT, running);
            coordinator.classify(TerminationCoordinator.OperationState.PRE_START, queued);
            return;
        }
        OperationExecutionKernel.TerminalClass terminalClass = operationTerminalClass();
        if (terminalClass == null) {
            coordinator.classify(dispatched
                    ? TerminationCoordinator.OperationState.UNKNOWN
                    : TerminationCoordinator.OperationState.PRE_START);
            return;
        }
        TerminationCoordinator.OperationState state = switch (terminalClass) {
            case PRE_START -> TerminationCoordinator.OperationState.PRE_START;
            case IN_FLIGHT -> TerminationCoordinator.OperationState.IN_FLIGHT;
            case SETTLED -> TerminationCoordinator.OperationState.SETTLED;
        };
        coordinator.classify(state, logicalOperations);
    }

    boolean markResourcesClosed() {
        if (resourcesClosed) return false;
        resourcesClosed = true;
        if (operationSession != null) operationSession.close();
        return true;
    }

    List<ItemStack> confirmedOutputs() { return confirmedOutputs; }

    @Nullable MachineLeaseRegistry.Lease machineLease() {
        return operationSession != null ? operationSession.machineLease() : machineLease;
    }

    int waitTicks() { return waitTicks; }

    boolean isWaitingForLeasedMachine() {
        return delegate instanceof ParallelCraftGroup group && group.isWaitingForLeasedMachine();
    }

    String failureReason() { return failureReason; }

    CraftFailureCode failureCode() { return failureCode; }

    int completedOperations() {
        return delegate instanceof ParallelCraftGroup group
                ? group.getCompletedOperations()
                : terminal && failureReason == null ? logicalOperations : 0;
    }

    int totalOperations() {
        return delegate instanceof ParallelCraftGroup group
                ? group.getTotalOperations() : logicalOperations;
    }

    int runningOperations() {
        if (delegate instanceof ParallelCraftGroup group) return group.getRunningOperations();
        return terminal ? 0 : logicalOperations;
    }

    String machineLabel() {
        if (delegate instanceof ParallelCraftGroup group) return group.machineLabel();
        MachineLeaseRegistry.Lease lease = machineLease();
        if (lease == null) return "";
        var machine = lease.machine();
        return machine.dimension() + "@" + machine.position().toShortString();
    }

    // ── Worker ──────────────────────────────────────────────────

    @Override
    public ConcurrentNodeExecutor.Observation observe() {
        if (delegate == null || failureReason != null) {
            return ConcurrentNodeExecutor.Observation.FAILED;
        }
        long observeStartNanos = System.nanoTime();
        try {
            // Graph workers previously passed null here. Delegates that resolve
            // machine state through the supplied level (notably Goety rituals)
            // then dereferenced a null ServerLevel during recursive crafts.
            ServerLevel observeLevel = resolveObservationLevel();
            if (observeLevel == null) {
                failureReason = "graph observation level unavailable";
                failureCode = CraftFailureCode.CHUNK_UNLOADED;
                return ConcurrentNodeExecutor.Observation.FAILED;
            }
            long currentTick = observeLevel.getGameTime();
            int elapsedTicks = lastObservationTick == Long.MIN_VALUE ? 1
                    : (int) Math.min(Integer.MAX_VALUE, Math.max(1L, currentTick - lastObservationTick));
            lastObservationTick = currentTick;
            // 分片跳过的 tick 仍计入无进展超时，不能把少轮询当成延长机器寿命。
            waitTicks = (int) Math.min(Integer.MAX_VALUE, (long) waitTicks + elapsedTicks);
            if (delegate instanceof ParallelCraftGroup group && group.isDraining()) {
                drainingTicks = (int) Math.min(Integer.MAX_VALUE, (long) drainingTicks + elapsedTicks);
            } else {
                drainingTicks = 0;
            }
            MachineOperation.CraftObservation observation;
            try {
                observation = operation.poll(observeLevel);
            } catch (Throwable error) {
                // A broken optional-mod delegate must fail its node cleanly.  In
                // particular, never let a null ServerLevel from a cross-dimension
                // machine abort the whole graph worker thread.
                failureReason = "machine observation crashed: "
                        + (error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
                failureCode = CraftFailureCode.INTERNAL_ERROR;
                RSIntegrationMod.LOGGER.error(
                        "[RSI] Graph node {} observation failed", nodeId, error);
                return ConcurrentNodeExecutor.Observation.FAILED;
            }
            if (delegate instanceof ParallelCraftGroup group) {
                if (group.isWaitingForLeasedMachine()) waitTicks = 0;
                long productionSequence = group.getProductionSequence();
                if (productionSequence > observedProductionSequence) {
                    observedProductionSequence = productionSequence;
                    waitTicks = 0;
                    progressObserved = true;
                }
                int completed = group.getCompletedOperations();
                if (completed > observedCompletedOperations) {
                    observedCompletedOperations = completed;
                    waitTicks = 0;
                    progressObserved = true;
                }
            }
            if (observation.phase() == MachineOperation.CraftPhase.FAILED) {
                failureReason = observation.detail();
                failureCode = CraftFailureCode.UNKNOWN;
                return ConcurrentNodeExecutor.Observation.FAILED;
            }
            if (observation.phase() == MachineOperation.CraftPhase.WORKING
                    && observation.completedWork() > observedCompletedWork) {
                observedCompletedWork = observation.completedWork();
                waitTicks = 0;
                // 同时通知整条合成链，避免节点正常推进时被全链超时终止。
                progressObserved = true;
            }
            List<ItemStack> capturedSnapshot = new ArrayList<>();
            if (operationSession != null) capturedSnapshot.addAll(operationSession.capturedSnapshot());
            if (capture != null) capturedSnapshot.addAll(capture.snapshot());
            long capturedCount = capturedSnapshot.stream().mapToLong(ItemStack::getCount).sum();
            if (capturedCount > observedCapturedCount) {
                observedCapturedCount = capturedCount;
                waitTicks = 0;
                progressObserved = true;
            }
            boolean capturedWorldOutput = OutputAccounting.hasCapturedExpectedProduction(
                    delegate, capturedSnapshot);
            boolean hasWorldCapture = capture != null
                    || (operationSession != null && operationSession.hasCaptureScope());
            boolean requiresWorldCapture = hasWorldCapture
                    && !delegate.canCollectResultWithoutWorldCapture()
                    && !OutputAccounting.permitsEmptyResult(delegate.outputContract());
            // 先确认整批实物数量；声明的 NBT 和副产物在收集后统一校验。
            if ((!requiresWorldCapture && observation.phase() == MachineOperation.CraftPhase.DONE)
                    || capturedWorldOutput) {
                if (!delegate.validateExecutionContext(player)) {
                    failureReason = "execution context changed before output publication";
                    failureCode = CraftFailureCode.RESOURCE_CONFLICT;
                    return ConcurrentNodeExecutor.Observation.FAILED;
                }
                doSucceed();
                return ConcurrentNodeExecutor.Observation.SUCCEEDED;
            }
        } catch (Exception e) {
            failureReason = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            failureCode = CraftFailureCode.INTERNAL_ERROR;
            return ConcurrentNodeExecutor.Observation.FAILED;
        } finally {
            PerformanceMonitor.recordDelegateObserve(delegate.getClass().getSimpleName(),
                    System.nanoTime() - observeStartNanos);
        }

        // Timeout: if this node is draining, give it extra time
        int timeoutTicks = RSIntegrationConfig.MULTIBLOCK_CRAFT_TIMEOUT_SECONDS.get() * 20;
        if (waitTicks > timeoutTicks) {
            if (drainingTicks > 0) {
                int drainLimit = Math.max(timeoutTicks * 4, 20 * 60);
                if (drainingTicks > drainLimit) {
                    failureReason = "Timeout draining " + nodeLabel;
                    failureCode = CraftFailureCode.TIMEOUT;
                    return ConcurrentNodeExecutor.Observation.FAILED;
                }
                waitTicks = 0;
                return ConcurrentNodeExecutor.Observation.WORKING;
            }
            failureReason = "Timeout waiting for " + nodeLabel;
            failureCode = CraftFailureCode.TIMEOUT;
            return ConcurrentNodeExecutor.Observation.FAILED;
        }

        return ConcurrentNodeExecutor.Observation.WORKING;
    }

    @Nullable
    private ServerLevel resolveObservationLevel() {
        if (player == null || player.getServer() == null) return null;
        ResourceLocation dimension = null;
        if (delegate instanceof AbstractBatchDelegate base) {
            dimension = base.getMachineDim();
        }
        if (dimension == null && machineLease != null) {
            dimension = machineLease.machine().dimension();
        }
        if (dimension != null) {
            ServerLevel resolved = player.getServer().getLevel(
                    ResourceKey.create(Registries.DIMENSION, dimension));
            if (resolved != null) return resolved;
        }
        return player.serverLevel();
    }

    @Override
    public void stopDispatch() {
        stopRequested = true;
        if (delegate instanceof ParallelCraftGroup group) {
            group.stopDispatch();
        }
    }

    @Override
    public void cleanupFailure() {
        cleanupFailure(failureReason != null ? failureReason : "node failure");
    }

    void cleanupFailure(String reason) {
        if (virtualInventory != null) {
            while (!queuedPublications.isEmpty()) {
                virtualInventory.add(queuedPublications.removeFirst().stack());
            }
        }
        if (terminal) return;
        terminal = true;
        if (delegate != null) {
            List<ItemStack> held = operation.retireHolding();
            if (virtualInventory != null) virtualInventory.addAll(held);
        }
        if (delegate != null) {
            boolean recoverReusable = !reusableMaterialsRecovered && player != null;
            if (recoverReusable) reusableMaterialsRecovered = true;
            runFailureCleanup(recoverReusable,
                    () -> releaseReusableMaterials(player),
                    () -> {
                        operation.recover(null, reason);
                        failureRecoveredInputs = delegate.failureRecoveredInputs();
                        if (delegate instanceof AbstractBatchDelegate abstractDelegate) {
                            physicalFailureCleanupCompleted =
                                    abstractDelegate.physicalFailureCleanupCompleted();
                        } else if (delegate instanceof ParallelCraftGroup group) {
                            physicalFailureCleanupCompleted =
                                    group.physicalFailureCleanupCompleted();
                        }
                    });
        }
        drainSettledIntoVirtual();
        List<ItemStack> captured = disarmCapture();
        if (virtualInventory != null) virtualInventory.addAll(captured);
    }

    static void runFailureCleanup(boolean recoverReusable, Runnable reusableRecovery,
                                  Runnable machineCleanup) {
        if (recoverReusable) {
            try {
                reusableRecovery.run();
            } catch (RuntimeException ignored) {
                // Machine cleanup must still run even if catalyst recovery fails.
            }
        }
        try {
            machineCleanup.run();
        } catch (RuntimeException ignored) {
            // Best-effort cleanup.
        }
    }

    List<ItemStack> drainSettledResults() {
        if (delegate instanceof ParallelCraftGroup group) {
            return group.drainSettledResults();
        }
        return List.of();
    }

    List<ItemStack> drainQueuedMaterials() {
        if (delegate instanceof ParallelCraftGroup group) {
            return group.drainQueuedMaterialsForRecovery();
        }
        return List.of();
    }

    List<ItemStack> drainQueuedProducerMaterials() {
        if (delegate instanceof ParallelCraftGroup group) {
            return group.drainQueuedProducerMaterialsForRecovery();
        }
        return List.of();
    }

    List<ExtractionLedger.ReservationToken> queuedReservationTokens() {
        if (delegate instanceof ParallelCraftGroup group) {
            return group.queuedReservationTokensForRecovery();
        }
        return List.of();
    }

    void refundQueuedBulkMaterials(@Nullable ServerPlayer online) {
        if (delegate instanceof ParallelCraftGroup group) group.refundQueuedBulkMaterials(online);
    }

    private void doSucceed() {
        if (terminal) return;
        confirmedOutputs = delegate == null ? disarmCapture()
                : operation.collect(player, logicalOperations, this::disarmCapture).stacks();
        if (delegate != null) {
            // 催化剂返还也是图的实物输出，必须在机器清理前取出并参与结算。
            List<ItemStack> completed = new ArrayList<>(confirmedOutputs);
            completed.addAll(delegate.collectReusableMaterials(player));
            confirmedOutputs = List.copyOf(completed);
        }
        // 收取实物失败时仍允许失败清理，不能提前封死材料回收入口。
        terminal = true;
        if (delegate != null) {
            try {
                operation.cleanup(player);
                releaseReusableMaterials(player);
            } catch (Exception ignored) {
                // Best-effort
            }
        }
    }

    private void drainSettledIntoVirtual() {
        if (virtualInventory == null) return;
        List<ItemStack> settled = drainSettledResults();
        for (ItemStack s : settled) {
            if (s != null && !s.isEmpty()) virtualInventory.add(s.copy());
        }
        List<ItemStack> queued = drainQueuedMaterials();
        for (ItemStack s : queued) {
            if (s != null && !s.isEmpty()) virtualInventory.add(s.copy());
        }
    }

    private void releaseReusableMaterials(@Nullable ServerPlayer online) {
        if (delegate == null || online == null) return;
        delegate.releaseReusableMaterials(online);
        if (delegate instanceof ParallelCraftGroup) return;
        if (reusableReservationsSettled || reusableReservationTokens.isEmpty()
                || nodeLedger == null || !nodeLedger.isCommitted()
                || operationSession == null || !operationSession.startAttempted()) return;
        for (ExtractionLedger.ReservationToken token : reusableReservationTokens) {
            nodeLedger.settleCommitted(token);
        }
        reusableReservationsSettled = true;
    }

    List<ItemStack> disarmCapture() {
        List<ItemStack> drained = new ArrayList<>();
        if (operationSession != null) drained.addAll(operationSession.drainCapture());
        CaptureSession handle = capture;
        capture = null;
        if (handle != null) drained.addAll(handle.drainAndClose());
        return List.copyOf(drained);
    }

    boolean isDraining() {
        return stopRequested
                || (delegate instanceof ParallelCraftGroup group && group.isDraining());
    }

    String describe() {
        return nodeLabel + " (" + nodeId + ")";
    }
}
