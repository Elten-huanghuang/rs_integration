package com.huanghuang.rsintegration.crafting;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** Runs an idempotent, audited termination transaction over chain-owned cleanup callbacks. */
public final class TerminationService {
    public enum Policy {
        REFUND_AND_DELIVER(true, true, false),
        SILENT_REFUND(true, true, true),
        NO_REFUND(false, false, false);

        private final boolean refundLedger;
        private final boolean deliverCaptured;
        private final boolean silent;

        Policy(boolean refundLedger, boolean deliverCaptured, boolean silent) {
            this.refundLedger = refundLedger;
            this.deliverCaptured = deliverCaptured;
            this.silent = silent;
        }

        public boolean silent() { return silent; }
    }

    public interface Actions {
        void classify(Session session);
        void settleCaptured(boolean deliverCaptured);
        void closeOperationScope();
        void cleanupGraph();
        void recoverGraphSurplus();
        void refundLedger();
        void deliverSettledAssets();
        void closeLedger();
        void notifyOwner();
    }

    public Session begin(UUID craftId, TerminationCoordinator.Cause cause, String reason) {
        return new Session(new TerminationCoordinator(craftId, cause, reason));
    }

    /** Executes the invariant termination order and returns its immutable audit. */
    public TerminationCoordinator.Report terminate(UUID craftId, TerminationCoordinator.Cause cause,
                                                   String reason, Policy policy, Actions actions) {
        Session session = begin(craftId, cause, reason);
        actions.classify(session);
        session.step("flat-capture", () -> actions.settleCaptured(policy.deliverCaptured));
        session.step("flat-operation-scope", actions::closeOperationScope);
        session.step("graph-runtime-cleanup", actions::cleanupGraph);
        session.step("graph-surplus-recovery", actions::recoverGraphSurplus);
        if (policy.refundLedger) session.step("ledger-refund", actions::refundLedger);
        session.step("settled-asset-delivery", actions::deliverSettledAssets);
        session.step("ledger-close", actions::closeLedger);
        if (!policy.silent) session.step("terminal-notification", actions::notifyOwner);
        return session.finish();
    }

    public static final class Session {
        private final TerminationCoordinator coordinator;
        private final AtomicBoolean finished = new AtomicBoolean();

        private Session(TerminationCoordinator coordinator) {
            this.coordinator = coordinator;
        }

        public void classify(TerminationCoordinator.OperationState state) {
            ensureOpen();
            coordinator.classify(state);
        }

        public void step(String name, Runnable action) {
            ensureOpen();
            coordinator.run(name, action);
        }

        public TerminationCoordinator.Report finish() {
            if (!finished.compareAndSet(false, true)) return coordinator.report();
            return coordinator.report();
        }

        TerminationCoordinator coordinator() {
            ensureOpen();
            return coordinator;
        }

        private void ensureOpen() {
            if (finished.get()) throw new IllegalStateException("termination session already finished");
        }
    }
}
