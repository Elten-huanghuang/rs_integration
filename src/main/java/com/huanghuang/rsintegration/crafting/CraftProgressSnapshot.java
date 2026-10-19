package com.huanghuang.rsintegration.crafting;

import net.minecraft.world.item.ItemStack;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;

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
    public static final int MAX_TECHNICAL_DETAIL_LENGTH = 1024;
    /** 单次进度包最多携带的节点明细；完整数量仍由 completedNodes/totalNodes 表达。 */
    public static final int MAX_NODE_DETAILS = 4096;

    public CraftProgressSnapshot {
        result = result == null ? Result.RUNNING : result;
        reason = reason == null ? Reason.NONE : reason;
        technicalDetail = limitTechnicalDetail(technicalDetail);
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        if (nodes.size() > MAX_NODE_DETAILS) {
            nodes = selectNodeDetails(nodes, MAX_NODE_DETAILS);
        }
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
        UNKNOWN,
        NETWORK_UNAVAILABLE,
        NO_BOUND_MACHINE,
        INPUT_CONFLICT,
        SPECIAL_RESOURCE_INSUFFICIENT;

        public static Reason fromOrdinal(int ordinal) {
            Reason[] values = values();
            return ordinal >= 0 && ordinal < values.length ? values[ordinal] : UNKNOWN;
        }

        public String translationKey() {
            return "rsi.progress.reason." + name().toLowerCase(Locale.ROOT);
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
            technicalDetail = technicalDetail == null ? "" : limitTechnicalDetail(technicalDetail);
        }

        @Override
        public ItemStack displayOutput() {
            return displayOutput.copy();
        }
    }

    public boolean isTerminal() {
        return sequence == TERMINAL_SEQUENCE || result.terminal();
    }

    /**
     * 大型图只保留最有诊断价值的节点明细，避免进度包越过协议上限。
     * 汇总计数不受影响，客户端仍能准确显示完成/运行数量。
     */
    public static List<NodeProgress> selectNodeDetails(List<NodeProgress> nodes, int maximum) {
        if (nodes == null || nodes.isEmpty() || nodes.size() <= maximum) {
            return nodes == null ? List.of() : List.copyOf(nodes);
        }
        int limit = Math.max(1, maximum);
        List<NodeProgress> selected = new ArrayList<>(limit);
        Set<Integer> selectedIds = new HashSet<>(limit);
        addNodeDetails(nodes, selected, selectedIds, limit,
                node -> node.state() == NodeState.RUNNING
                        || node.state() == NodeState.FAILED
                        || node.draining());
        addNodeDetails(nodes, selected, selectedIds, limit,
                node -> node.state() == NodeState.READY);
        addNodeDetails(nodes, selected, selectedIds, limit,
                node -> node.state() == NodeState.BLOCKED);
        addNodeDetails(nodes, selected, selectedIds, limit,
                node -> node.reason() != Reason.NONE
                        || !node.technicalDetail().isBlank());
        addNodeDetails(nodes, selected, selectedIds, limit, node -> true);
        return List.copyOf(selected);
    }

    private static void addNodeDetails(List<NodeProgress> source,
                                       List<NodeProgress> selected,
                                       Set<Integer> selectedIds,
                                       int limit,
                                       Predicate<NodeProgress> predicate) {
        if (selected.size() >= limit) return;
        for (NodeProgress node : source) {
            if (selected.size() >= limit) return;
            if (!predicate.test(node) || !selectedIds.add(node.nodeId())) continue;
            selected.add(node);
        }
    }

    @Nullable
    private static String limitTechnicalDetail(@Nullable String detail) {
        if (detail == null || detail.length() <= MAX_TECHNICAL_DETAIL_LENGTH) return detail;
        String suffix = " [truncated]";
        int end = MAX_TECHNICAL_DETAIL_LENGTH - suffix.length();
        if (Character.isHighSurrogate(detail.charAt(end - 1))) end--;
        return detail.substring(0, end) + suffix;
    }
}
