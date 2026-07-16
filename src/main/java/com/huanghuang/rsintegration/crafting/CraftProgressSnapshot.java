package com.huanghuang.rsintegration.crafting;

import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.List;
import java.util.UUID;

/** Immutable progress snapshot sent from server to client. */
public record CraftProgressSnapshot(
        UUID craftId,
        int sequence,
        Result result,
        Reason reason,
        int completedNodes,
        int totalNodes,
        int runningNodes,
        @Nullable String technicalDetail,
        List<NodeProgress> nodes
) {
    public static final int TERMINAL_SEQUENCE = Integer.MAX_VALUE;

    public CraftProgressSnapshot {
        result = result == null ? Result.RUNNING : result;
        reason = reason == null ? Reason.NONE : reason;
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
    }

    public CraftProgressSnapshot(UUID craftId, int sequence, Result result, Reason reason,
                                 int completedNodes, int totalNodes, int runningNodes,
                                 @Nullable String technicalDetail) {
        this(craftId, sequence, result, reason, completedNodes, totalNodes, runningNodes,
                technicalDetail, List.of());
    }

    public enum Result {
        RUNNING,
        WAITING,
        STOPPING,
        SUCCEEDED,
        FAILED,
        CANCELLED;

        public static Result fromOrdinal(int ordinal) {
            Result[] values = values();
            return ordinal >= 0 && ordinal < values.length ? values[ordinal] : RUNNING;
        }

        public boolean terminal() {
            return this == SUCCEEDED || this == FAILED || this == CANCELLED;
        }
    }

    public enum Reason {
        NONE,
        WAITING_MATERIALS,
        MACHINE_BUSY,
        CHUNK_UNLOADED,
        OPERATION_BUDGET,
        RESOURCE_CONFLICT,
        CONTRACT_INCOMPATIBLE,
        MATERIAL_EXTRACTION_FAILED,
        START_REJECTED,
        OUTPUT_MISSING,
        TIMEOUT,
        PLAYER_CANCELLED,
        PLAYER_OFFLINE,
        SERVER_STOP,
        INTERNAL_ERROR,
        UNKNOWN;

        public static Reason fromOrdinal(int ordinal) {
            Reason[] values = values();
            return ordinal >= 0 && ordinal < values.length ? values[ordinal] : UNKNOWN;
        }

        public String translationKey() {
            return "rsi.progress.reason." + name().toLowerCase(java.util.Locale.ROOT);
        }
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
            ItemStack displayOutput,
            int completedOperations,
            int totalOperations,
            int runningOperations,
            String machineLabel,
            Reason reason,
            String technicalDetail,
            boolean draining
    ) {
        public NodeProgress {
            state = state == null ? NodeState.UNKNOWN : state;
            recipeId = recipeId == null ? "" : recipeId;
            modTypeId = modTypeId == null ? "" : modTypeId;
            displayOutput = displayOutput == null || displayOutput.isEmpty()
                    ? ItemStack.EMPTY : displayOutput.copy();
            totalOperations = Math.max(0, totalOperations);
            completedOperations = Math.min(Math.max(0, completedOperations), totalOperations);
            runningOperations = Math.min(Math.max(0, runningOperations),
                    totalOperations - completedOperations);
            machineLabel = machineLabel == null ? "" : machineLabel;
            reason = reason == null ? Reason.NONE : reason;
            technicalDetail = technicalDetail == null ? "" : technicalDetail;
        }

        @Override
        public ItemStack displayOutput() {
            return displayOutput.copy();
        }
    }

    public boolean isTerminal() {
        return sequence == TERMINAL_SEQUENCE || result.terminal();
    }
}
