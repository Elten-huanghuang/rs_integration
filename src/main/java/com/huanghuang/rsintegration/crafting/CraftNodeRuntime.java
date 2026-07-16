package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.crafting.batch.IBatchDelegate;
import com.huanghuang.rsintegration.crafting.graph.ConcurrentNodeExecutor;
import com.huanghuang.rsintegration.crafting.graph.MachineLeaseRegistry;
import com.huanghuang.rsintegration.crafting.graph.NodeAdmissionCoordinator;
import com.huanghuang.rsintegration.crafting.graph.NodeId;
import com.huanghuang.rsintegration.crafting.graph.NodeOutputAccumulator;
import com.huanghuang.rsintegration.crafting.loadbalancer.ParallelCraftGroup;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.List;

/**
 * Wraps a single DAG node's physical delegate, ledger, capture handle, and timeout.
 * Exposes {@link ConcurrentNodeExecutor.Worker} so the graph scheduler can observe
 * and drain it alongside other running nodes.
 */
final class CraftNodeRuntime implements ConcurrentNodeExecutor.Worker {

    private final NodeId nodeId;
    private final String nodeLabel;
    private IBatchDelegate delegate;
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
    private boolean dispatched;
    private boolean resourcesClosed;
    private List<ItemStack> confirmedOutputs = List.of();
    private boolean terminalOutputsDrained;
    private int waitTicks;
    private int drainingTicks;
    private boolean stopRequested;
    private String failureReason;
    private boolean terminal;
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
        this.nodeLedger = nodeLedger;
        this.machineLease = machineLease;
        this.admission = admission;
        this.operationSession = operationSession;
    }

    void attachCapture(CaptureSession handle) {
        this.capture = handle;
    }

    void attachOutputs(NodeOutputAccumulator outputs) {
        this.outputs = outputs;
    }

    List<NodeOutputAccumulator.Publication> drainIncrementalOutputs() {
        if (outputs == null) return List.of();
        List<ItemStack> actual = new java.util.ArrayList<>(drainSettledResults());
        if (terminal && !terminalOutputsDrained) {
            terminalOutputsDrained = true;
            actual.addAll(confirmedOutputs);
        }
        return outputs.add(actual);
    }

    boolean outputsComplete() {
        return outputs == null || outputs.isComplete();
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

    void markStartFailed(String reason) {
        failureReason = reason;
    }

    void markCompletionFailed(String reason) {
        failureReason = reason;
    }

    String outputShortageDetail() {
        if (outputs == null) return "declared graph outputs were not fully collected";
        String detail = outputs.describeShortages();
        return detail.isEmpty() ? "declared graph outputs were not fully collected"
                : "output shortage: " + detail;
    }

    boolean wasDispatched() { return dispatched; }

    @Nullable OperationExecutionKernel.TerminalClass operationTerminalClass() {
        return operationSession == null ? null : operationSession.terminalClass();
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
            for (int i = 0; i < completed; i++) {
                coordinator.classify(TerminationCoordinator.OperationState.SETTLED);
            }
            for (int i = 0; i < running; i++) {
                coordinator.classify(TerminationCoordinator.OperationState.IN_FLIGHT);
            }
            for (int i = 0; i < queued; i++) {
                coordinator.classify(TerminationCoordinator.OperationState.PRE_START);
            }
            return;
        }
        OperationExecutionKernel.TerminalClass terminalClass = operationTerminalClass();
        if (terminalClass == null) {
            coordinator.classify(dispatched
                    ? TerminationCoordinator.OperationState.UNKNOWN
                    : TerminationCoordinator.OperationState.PRE_START);
            return;
        }
        coordinator.classify(switch (terminalClass) {
            case PRE_START -> TerminationCoordinator.OperationState.PRE_START;
            case IN_FLIGHT -> TerminationCoordinator.OperationState.IN_FLIGHT;
            case SETTLED -> TerminationCoordinator.OperationState.SETTLED;
        });
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

    String failureReason() { return failureReason; }

    int completedOperations() {
        return delegate instanceof ParallelCraftGroup group
                ? group.getCompletedOperations() : terminal && failureReason == null ? 1 : 0;
    }

    int totalOperations() {
        return delegate instanceof ParallelCraftGroup group ? group.getTotalOperations() : 1;
    }

    int runningOperations() {
        if (delegate instanceof ParallelCraftGroup group) return group.getRunningOperations();
        return terminal ? 0 : 1;
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
        waitTicks++;

        try {
            if (delegate instanceof ParallelCraftGroup group && group.isDraining()) {
                drainingTicks++;
            } else {
                drainingTicks = 0;
            }

            IBatchDelegate.CraftObservation observation = delegate.observeCraft(null);
            if (observation.phase() == IBatchDelegate.CraftPhase.FAILED) {
                failureReason = observation.detail();
                return ConcurrentNodeExecutor.Observation.FAILED;
            }
            if (observation.phase() == IBatchDelegate.CraftPhase.DONE) {
                doSucceed();
                return ConcurrentNodeExecutor.Observation.SUCCEEDED;
            }
        } catch (Exception e) {
            failureReason = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            return ConcurrentNodeExecutor.Observation.FAILED;
        }

        // Timeout: if this node is draining, give it extra time
        int timeoutTicks = RSIntegrationConfig.MULTIBLOCK_CRAFT_TIMEOUT_SECONDS.get() * 20;
        if (waitTicks > timeoutTicks) {
            if (drainingTicks > 0) {
                int drainLimit = Math.max(timeoutTicks * 4, 20 * 60);
                if (drainingTicks > drainLimit) {
                    failureReason = "Timeout draining " + nodeLabel;
                    return ConcurrentNodeExecutor.Observation.FAILED;
                }
                waitTicks = 0;
                return ConcurrentNodeExecutor.Observation.WORKING;
            }
            failureReason = "Timeout waiting for " + nodeLabel;
            return ConcurrentNodeExecutor.Observation.FAILED;
        }

        return ConcurrentNodeExecutor.Observation.WORKING;
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
        if (terminal) return;
        terminal = true;
        if (delegate != null) {
            try {
                delegate.onBatchFailed(null, failureReason != null ? failureReason : "node failure");
            } catch (Exception ignored) {
                // Best-effort cleanup
            }
        }
        disarmCapture();
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

    private void doSucceed() {
        if (terminal) return;
        terminal = true;
        List<ItemStack> outputs = new java.util.ArrayList<>();
        List<ItemStack> captured = disarmCapture();
        for (ItemStack s : captured) {
            if (s != null && !s.isEmpty()) outputs.add(s.copy());
        }
        List<ItemStack> results = collectResults(player);
        for (ItemStack s : results) {
            if (s != null && !s.isEmpty()) outputs.add(s.copy());
        }
        confirmedOutputs = List.copyOf(outputs);
        if (delegate != null) {
            try {
                delegate.onBatchFinished(player);
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

    List<ItemStack> collectResults(ServerPlayer player) {
        if (delegate == null) return List.of();
        return delegate.collectAllResults(player);
    }

    List<ItemStack> disarmCapture() {
        List<ItemStack> drained = new java.util.ArrayList<>();
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
