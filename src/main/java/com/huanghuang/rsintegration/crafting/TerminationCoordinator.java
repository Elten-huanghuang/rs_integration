package com.huanghuang.rsintegration.crafting;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Ordered, best-effort termination runner with an immutable audit report. */
public final class TerminationCoordinator {

    public enum Cause {
        FAILURE,
        CANCELLED,
        OFFLINE,
        SERVER_STOP,
        INTERNAL_ERROR
    }

    public enum OperationState {
        PRE_START,
        IN_FLIGHT,
        SETTLED,
        UNKNOWN
    }

    public record StepResult(String name, boolean succeeded, String detail) {
        public StepResult {
            Objects.requireNonNull(name, "name");
            detail = detail == null ? "" : detail;
        }
    }

    public record Report(UUID craftId, Cause cause, String reason,
                         long preStartOperations, long inFlightOperations,
                         long settledOperations, long unknownOperations,
                         List<StepResult> steps) {
        public Report {
            Objects.requireNonNull(craftId, "craftId");
            Objects.requireNonNull(cause, "cause");
            reason = reason == null ? "" : reason;
            steps = List.copyOf(steps);
        }

        public boolean clean() {
            return unknownOperations == 0 && steps.stream().allMatch(StepResult::succeeded);
        }

        public int failedSteps() {
            return (int) steps.stream().filter(step -> !step.succeeded()).count();
        }
    }

    private final UUID craftId;
    private final Cause cause;
    private final String reason;
    private final List<StepResult> steps = new ArrayList<>();
    private long preStartOperations;
    private long inFlightOperations;
    private long settledOperations;
    private long unknownOperations;

    public TerminationCoordinator(UUID craftId, Cause cause, String reason) {
        this.craftId = Objects.requireNonNull(craftId, "craftId");
        this.cause = Objects.requireNonNull(cause, "cause");
        this.reason = reason == null ? "" : reason;
    }

    public void classify(OperationState state) {
        classify(state, 1L);
    }

    /** 汇总分类不展开操作编号，取消千万量订单也只增加四个计数。 */
    public void classify(OperationState state, long count) {
        if (count < 0L) throw new IllegalArgumentException("negative operation count");
        switch (Objects.requireNonNull(state, "state")) {
            case PRE_START -> preStartOperations = Math.addExact(preStartOperations, count);
            case IN_FLIGHT -> inFlightOperations = Math.addExact(inFlightOperations, count);
            case SETTLED -> settledOperations = Math.addExact(settledOperations, count);
            case UNKNOWN -> unknownOperations = Math.addExact(unknownOperations, count);
        }
    }

    public void run(String name, Runnable action) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(action, "action");
        try {
            action.run();
            steps.add(new StepResult(name, true, ""));
        } catch (Exception e) {
            String detail = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            steps.add(new StepResult(name, false, detail));
        }
    }

    public Report report() {
        return new Report(craftId, cause, reason, preStartOperations,
                inFlightOperations, settledOperations, unknownOperations, steps);
    }
}
