package com.huanghuang.rsintegration.crafting.progress;

import net.minecraft.world.item.ItemStack;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.Objects;
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
        List<NodeProgress> nodes,
        CraftOutputProgress outputProgress,
        CraftProgressTiming timing
) {
    public static final int TERMINAL_SEQUENCE = Integer.MAX_VALUE;
    public static final int MAX_TECHNICAL_DETAIL_LENGTH = 1024;
    /** 单次进度包最多携带的节点明细；完整数量仍由 completedNodes/totalNodes 表达。 */
    public static final int MAX_NODE_DETAILS = 4096;
    public static final int MAX_WAITING_INPUTS = 16;

    public CraftProgressSnapshot {
        result = result == null ? Result.RUNNING : result;
        reason = reason == null ? Reason.NONE : reason;
        technicalDetail = limitTechnicalDetail(technicalDetail);
        outputProgress = outputProgress == null ? CraftOutputProgress.UNKNOWN : outputProgress;
        timing = timing == null ? CraftProgressTiming.UNKNOWN : timing;
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        if (nodes.size() > MAX_NODE_DETAILS) {
            nodes = selectNodeDetails(nodes, MAX_NODE_DETAILS);
        }
    }

    public CraftProgressSnapshot(UUID craftId, int sequence, Result result, Reason reason,
                                 int completedNodes, int totalNodes, int runningNodes,
                                 @Nullable String technicalDetail, List<NodeProgress> nodes,
                                 CraftOutputProgress outputProgress) {
        this(craftId, sequence, result, reason, completedNodes, totalNodes, runningNodes,
                technicalDetail, nodes, outputProgress, CraftProgressTiming.UNKNOWN);
    }

    public CraftProgressSnapshot(UUID craftId, int sequence, Result result, Reason reason,
                                 int completedNodes, int totalNodes, int runningNodes,
                                 @Nullable String technicalDetail, List<NodeProgress> nodes) {
        this(craftId, sequence, result, reason, completedNodes, totalNodes, runningNodes,
                technicalDetail, nodes, CraftOutputProgress.UNKNOWN);
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
        SPECIAL_RESOURCE_INSUFFICIENT,
        PRODUCTION_TARGET_UNMET;

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
            boolean draining,
            List<WaitingInput> waitingInputs,
            boolean waitingInputsTruncated,
            MachineCycleProgress machineProgress
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
            waitingInputs = waitingInputs == null ? List.of() : List.copyOf(waitingInputs);
            if (waitingInputs.size() > MAX_WAITING_INPUTS) {
                waitingInputs = List.copyOf(waitingInputs.subList(0, MAX_WAITING_INPUTS));
                waitingInputsTruncated = true;
            }
            machineProgress = machineProgress == null ? MachineCycleProgress.UNKNOWN : machineProgress;
        }

        public NodeProgress(int nodeId, NodeState state, String recipeId, String modTypeId, ItemStack displayOutput,
                            int completedOperations, int totalOperations, int runningOperations, String machineLabel,
                            Reason reason, String technicalDetail, boolean draining) {
            this(nodeId, state, recipeId, modTypeId, displayOutput, completedOperations, totalOperations,
                    runningOperations, machineLabel, reason, technicalDetail, draining, List.of(), false,
                    MachineCycleProgress.UNKNOWN);
        }

        @Override
        public ItemStack displayOutput() {
            return displayOutput.copy();
        }

        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof NodeProgress node)) return false;
            return nodeId == node.nodeId && state == node.state && recipeId.equals(node.recipeId)
                    && modTypeId.equals(node.modTypeId) && ItemStack.matches(displayOutput, node.displayOutput)
                    && completedOperations == node.completedOperations && totalOperations == node.totalOperations
                    && runningOperations == node.runningOperations && machineLabel.equals(node.machineLabel)
                    && reason == node.reason && technicalDetail.equals(node.technicalDetail) && draining == node.draining
                    && waitingInputs.equals(node.waitingInputs) && waitingInputsTruncated == node.waitingInputsTruncated
                    && machineProgress.equals(node.machineProgress);
        }

        @Override public int hashCode() {
            return Objects.hash(nodeId, state, recipeId, modTypeId, displayOutput.getItem(), displayOutput.getCount(),
                    displayOutput.getTag(), completedOperations, totalOperations, runningOperations, machineLabel,
                    reason, technicalDetail, draining, waitingInputs, waitingInputsTruncated, machineProgress);
        }
    }

    /** producerNodeId 为 -1 时来自外部材料池，数量是当前尚不能满足的实际请求量。 */
    public record WaitingInput(ItemStack material, long quantity, int producerNodeId) {
        public WaitingInput {
            if (material == null || material.isEmpty() || quantity <= 0 || producerNodeId < -1) {
                throw new IllegalArgumentException("invalid waiting input");
            }
            material = material.copyWithCount(1);
        }

        @Override public ItemStack material() { return material.copy(); }

        @Override public boolean equals(Object other) {
            return this == other || other instanceof WaitingInput input && quantity == input.quantity
                    && producerNodeId == input.producerNodeId && ItemStack.matches(material, input.material);
        }

        @Override public int hashCode() {
            return Objects.hash(material.getItem(), material.getTag(), quantity, producerNodeId);
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
