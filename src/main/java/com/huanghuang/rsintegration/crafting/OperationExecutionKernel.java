package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.crafting.graph.MachineLeaseRegistry;
import com.huanghuang.rsintegration.crafting.graph.NodeId;
import com.huanghuang.rsintegration.crafting.graph.OperationBudget;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Shared start-attempt boundary for one physical crafting operation. */
public final class OperationExecutionKernel {
    public enum TerminalClass {
        PRE_START,
        IN_FLIGHT,
        SETTLED
    }

    public enum CompletionResult {
        SUCCEEDED,
        OUTPUT_SHORTAGE
    }

    private final OperationResourceCoordinator resources;

    public OperationExecutionKernel(OperationResourceCoordinator resources) {
        this.resources = Objects.requireNonNull(resources, "resources");
    }

    @Nullable
    public Session tryPrepareBudget(OperationBudget craftBudget) {
        OperationResourceCoordinator.Scope scope = resources.tryAcquireBudget(craftBudget);
        return scope == null ? null : new Session(scope);
    }

    @Nullable
    public Session tryPrepare(UUID craftId, NodeId nodeId, int operationId,
                              OperationBudget craftBudget,
                              MachineLeaseRegistry.MachineKey machine,
                              @Nullable OperationResourceCoordinator.CaptureRequest capture) {
        return tryPrepare(craftId, nodeId, operationId, craftBudget, List.of(machine), capture);
    }

    @Nullable
    public Session tryPrepare(UUID craftId, NodeId nodeId, int operationId,
                              OperationBudget craftBudget,
                              List<MachineLeaseRegistry.MachineKey> machineScope,
                              @Nullable OperationResourceCoordinator.CaptureRequest capture) {
        OperationResourceCoordinator.Scope scope = resources.tryAcquire(
                craftId, nodeId, operationId, craftBudget, machineScope, capture);
        return scope == null ? null : new Session(scope);
    }

    /** Create a lifecycle-only session for synchronous operations with no physical resource scope. */
    public Session prepareLogical() {
        return new Session(null);
    }

    @FunctionalInterface
    public interface StartAction {
        boolean start();
    }

    @FunctionalInterface
    public interface CommitAction {
        boolean commit();
    }

    @FunctionalInterface
    public interface SettlementAction {
        void settle();
    }

    @FunctionalInterface
    public interface OutputValidator {
        boolean validate();
    }

    public static final class Session implements AutoCloseable {
        @Nullable
        private OperationResourceCoordinator.Scope scope;
        private boolean closed;
        private boolean committed;
        private boolean startAttempted;
        private boolean settled;

        private Session(@Nullable OperationResourceCoordinator.Scope scope) {
            this.scope = scope;
        }

        public boolean commit(CommitAction action) {
            Objects.requireNonNull(action, "action");
            if (closed || committed || startAttempted) {
                throw new IllegalStateException("operation cannot commit in its current state");
            }
            committed = action.commit();
            return committed;
        }

        public boolean tryStart(StartAction action) {
            Objects.requireNonNull(action, "action");
            if (closed || !committed || startAttempted) {
                throw new IllegalStateException("operation must commit exactly once before start");
            }
            startAttempted = true;
            if (scope != null) scope.markStartAttempted();
            return action.start();
        }

        public CompletionResult complete(OutputValidator outputs, SettlementAction settlement) {
            Objects.requireNonNull(outputs, "outputs");
            Objects.requireNonNull(settlement, "settlement");
            if (closed || !startAttempted || settled) {
                throw new IllegalStateException("operation cannot complete in its current state");
            }
            boolean outputComplete = outputs.validate();
            settle(settlement);
            return outputComplete ? CompletionResult.SUCCEEDED : CompletionResult.OUTPUT_SHORTAGE;
        }

        public void settle(SettlementAction action) {
            Objects.requireNonNull(action, "action");
            if (closed || !startAttempted || settled) {
                throw new IllegalStateException("operation cannot settle in its current state");
            }
            action.settle();
            settled = true;
        }

        public TerminalClass terminalClass() {
            if (settled) return TerminalClass.SETTLED;
            return startAttempted ? TerminalClass.IN_FLIGHT : TerminalClass.PRE_START;
        }

        public boolean committed() {
            return committed;
        }

        public boolean settled() {
            return settled;
        }

        public boolean hasCaptured() {
            return scope != null && scope.hasCaptured();
        }

        public List<ItemStack> capturedSnapshot() {
            return scope == null ? List.of() : scope.capturedSnapshot();
        }

        public List<ItemStack> drainCapture() {
            return scope == null ? List.of() : scope.drainCapture();
        }

        public MachineLeaseRegistry.Lease machineLease() {
            return scope == null ? null : scope.machineLease();
        }

        public boolean startAttempted() {
            return startAttempted;
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            OperationResourceCoordinator.Scope current = scope;
            scope = null;
            if (current != null) current.close();
        }
    }
}
