package com.huanghuang.rsintegration.crafting;

import javax.annotation.Nullable;
import java.util.List;
import java.util.UUID;

/** Immutable progress snapshot sent from server to client. */
public record CraftProgressSnapshot(
        UUID craftId,
        int sequence,
        byte chainState,   // 0=EXECUTING, 1=STOPPING
        int completedNodes,
        int totalNodes,
        int runningNodes,
        @Nullable String failedStep,
        List<NodeProgress> nodes
) {
    public static final byte STATE_EXECUTING = 0;
    public static final byte STATE_STOPPING = 1;
    public static final int TERMINAL_SEQUENCE = Integer.MAX_VALUE;

    public CraftProgressSnapshot {
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
    }

    public CraftProgressSnapshot(UUID craftId, int sequence, byte chainState,
                                 int completedNodes, int totalNodes, int runningNodes,
                                 @Nullable String failedStep) {
        this(craftId, sequence, chainState, completedNodes, totalNodes, runningNodes,
                failedStep, List.of());
    }

    public enum NodeState {
        UNKNOWN,
        BLOCKED,
        READY,
        RUNNING,
        SUCCEEDED,
        FAILED,
        CANCELLED;

        public static NodeState fromOrdinal(int ordinal) {
            NodeState[] values = values();
            return ordinal >= 0 && ordinal < values.length ? values[ordinal] : UNKNOWN;
        }
    }

    public record NodeProgress(
            int nodeId,
            NodeState state,
            String recipeId,
            String modTypeId,
            int completedOperations,
            int totalOperations,
            int runningOperations,
            String machineLabel,
            String detail,
            boolean draining
    ) {
        public NodeProgress {
            state = state == null ? NodeState.UNKNOWN : state;
            recipeId = recipeId == null ? "" : recipeId;
            modTypeId = modTypeId == null ? "" : modTypeId;
            totalOperations = Math.max(0, totalOperations);
            completedOperations = Math.min(Math.max(0, completedOperations), totalOperations);
            runningOperations = Math.min(Math.max(0, runningOperations),
                    totalOperations - completedOperations);
            machineLabel = machineLabel == null ? "" : machineLabel;
            detail = detail == null ? "" : detail;
        }
    }

    public boolean isTerminal() {
        return sequence == TERMINAL_SEQUENCE || chainState == STATE_STOPPING;
    }
}
