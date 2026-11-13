package com.huanghuang.rsintegration.crafting.batch;

import java.lang.reflect.Field;

import com.huanghuang.rsintegration.crafting.progress.CraftProgressSnapshot;
import com.huanghuang.rsintegration.crafting.progress.CraftOutputProgress;
import com.huanghuang.rsintegration.crafting.progress.CraftProgressTiming;
import com.huanghuang.rsintegration.crafting.progress.MachineCycleProgress;
import com.huanghuang.rsintegration.mods.ironsspellbooks.InkFluidSupport;
import com.huanghuang.rsintegration.util.PerformanceMonitor;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/** S2C progress update for one running or terminal craft. */
public final class CraftProgressPacket {

    static final int MAX_NODES = CraftProgressSnapshot.MAX_NODE_DETAILS;
    static final int MAX_TECHNICAL_DETAIL_LENGTH = CraftProgressSnapshot.MAX_TECHNICAL_DETAIL_LENGTH;
    static final int MAX_RECIPE_ID_LENGTH = 256;
    static final int MAX_MOD_TYPE_ID_LENGTH = 128;
    static final int MAX_MACHINE_LABEL_LENGTH = 256;

    private final CraftProgressSnapshot snapshot;

    public CraftProgressPacket(CraftProgressSnapshot snapshot) {
        this.snapshot = snapshot;
    }

    public void encode(FriendlyByteBuf buf) {
        int startIndex = buf.writerIndex();
        buf.writeUUID(snapshot.craftId());
        buf.writeVarInt(snapshot.sequence());
        buf.writeVarInt(snapshot.result().ordinal());
        buf.writeVarInt(snapshot.reason().ordinal());
        buf.writeVarInt(requireNonNegative(snapshot.completedNodes(), "completedNodes"));
        buf.writeVarInt(requireNonNegative(snapshot.totalNodes(), "totalNodes"));
        buf.writeVarInt(requireNonNegative(snapshot.runningNodes(), "runningNodes"));
        buf.writeBoolean(snapshot.technicalDetail() != null);
        if (snapshot.technicalDetail() != null) {
            buf.writeUtf(snapshot.technicalDetail(), MAX_TECHNICAL_DETAIL_LENGTH);
        }
        List<CraftProgressSnapshot.NodeProgress> nodes = snapshot.nodes();
        if (nodes.size() > MAX_NODES) {
            throw new IllegalArgumentException("too many progress nodes: " + nodes.size());
        }
        buf.writeVarInt(nodes.size());
        for (CraftProgressSnapshot.NodeProgress node : nodes) {
            int completedOperations = requireNonNegative(node.completedOperations(), "completedOperations");
            int totalOperations = requireNonNegative(node.totalOperations(), "totalOperations");
            int runningOperations = requireNonNegative(node.runningOperations(), "runningOperations");
            validateOperationCountsForEncode(completedOperations, totalOperations, runningOperations);
            buf.writeVarInt(requireNonNegative(node.nodeId(), "nodeId"));
            buf.writeVarInt(node.state().ordinal());
            buf.writeUtf(node.recipeId(), MAX_RECIPE_ID_LENGTH);
            buf.writeUtf(node.modTypeId(), MAX_MOD_TYPE_ID_LENGTH);
            InkFluidSupport.writePacket(buf, node.displayOutput());
            buf.writeVarInt(completedOperations);
            buf.writeVarInt(totalOperations);
            buf.writeVarInt(runningOperations);
            buf.writeUtf(node.machineLabel(), MAX_MACHINE_LABEL_LENGTH);
            buf.writeVarInt(node.reason().ordinal());
            buf.writeUtf(node.technicalDetail(), MAX_TECHNICAL_DETAIL_LENGTH);
            buf.writeBoolean(node.draining());
            writeNodeMonitorData(buf, node);
        }
        writeProgressTail(buf, snapshot.outputProgress(), snapshot.timing());
        PerformanceMonitor.recordProgressPacketBytes(buf.writerIndex() - startIndex);
    }

    public static CraftProgressPacket decode(FriendlyByteBuf buf) {
        UUID craftId = buf.readUUID();
        int sequence = buf.readVarInt();
        CraftProgressSnapshot.Result result = CraftProgressSnapshot.Result.fromOrdinal(buf.readVarInt());
        CraftProgressSnapshot.Reason reason = CraftProgressSnapshot.Reason.fromOrdinal(buf.readVarInt());
        int completed = readNonNegative(buf, "completedNodes");
        int total = readNonNegative(buf, "totalNodes");
        int running = readNonNegative(buf, "runningNodes");
        String detail = buf.readBoolean() ? buf.readUtf(MAX_TECHNICAL_DETAIL_LENGTH) : null;
        int count = readBoundedCount(buf);
        List<CraftProgressSnapshot.NodeProgress> nodes = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int nodeId = readNonNegative(buf, "nodeId");
            CraftProgressSnapshot.NodeState nodeState =
                    CraftProgressSnapshot.NodeState.fromOrdinal(buf.readVarInt());
            String recipeId = buf.readUtf(MAX_RECIPE_ID_LENGTH);
            String modTypeId = buf.readUtf(MAX_MOD_TYPE_ID_LENGTH);
            ItemStack displayOutput = InkFluidSupport.readPacket(buf);
            int completedOperations = readNonNegative(buf, "completedOperations");
            int totalOperations = readNonNegative(buf, "totalOperations");
            int runningOperations = readNonNegative(buf, "runningOperations");
            validateOperationCounts(completedOperations, totalOperations, runningOperations);
            String machineLabel = buf.readUtf(MAX_MACHINE_LABEL_LENGTH);
            CraftProgressSnapshot.Reason nodeReason =
                    CraftProgressSnapshot.Reason.fromOrdinal(buf.readVarInt());
            String nodeDetail = buf.readUtf(MAX_TECHNICAL_DETAIL_LENGTH);
            boolean draining = buf.readBoolean();
            nodes.add(readNodeMonitorData(buf, new CraftProgressSnapshot.NodeProgress(nodeId, nodeState,
                    recipeId, modTypeId, displayOutput, completedOperations, totalOperations,
                    runningOperations, machineLabel, nodeReason, nodeDetail, draining)));
        }
        var tail = readProgressTail(buf);
        return new CraftProgressPacket(new CraftProgressSnapshot(craftId, sequence, result, reason,
                completed, total, running, detail, List.copyOf(nodes), tail.output(), tail.timing()));
    }

    /** 全量和增量共用同一份监控字段编码，防止等待关系在更新后丢失。 */
    static void writeNodeMonitorData(FriendlyByteBuf buf, CraftProgressSnapshot.NodeProgress node) {
        buf.writeVarInt(node.waitingInputs().size());
        for (var input : node.waitingInputs()) {
            InkFluidSupport.writePacket(buf, input.material());
            buf.writeVarLong(input.quantity());
            buf.writeVarInt(input.producerNodeId());
        }
        buf.writeBoolean(node.waitingInputsTruncated());
        var cycle = node.machineProgress();
        buf.writeVarLong(cycle.completedTicks());
        buf.writeVarLong(cycle.totalTicks());
        buf.writeVarInt(cycle.reportingMachines());
        buf.writeVarInt(cycle.activeMachines());
    }

    static CraftProgressSnapshot.NodeProgress readNodeMonitorData(FriendlyByteBuf buf,
                                                                  CraftProgressSnapshot.NodeProgress node) {
        int count = buf.readVarInt();
        if (count < 0 || count > CraftProgressSnapshot.MAX_WAITING_INPUTS) {
            throw new DecoderException("waiting input count out of bounds");
        }
        var inputs = new ArrayList<CraftProgressSnapshot.WaitingInput>(count);
        try {
            for (int i = 0; i < count; i++) {
                inputs.add(new CraftProgressSnapshot.WaitingInput(InkFluidSupport.readPacket(buf),
                        buf.readVarLong(), buf.readVarInt()));
            }
            boolean truncated = buf.readBoolean();
            var cycle = new MachineCycleProgress(buf.readVarLong(), buf.readVarLong(), buf.readVarInt(), buf.readVarInt());
            return new CraftProgressSnapshot.NodeProgress(node.nodeId(), node.state(), node.recipeId(), node.modTypeId(),
                    node.displayOutput(), node.completedOperations(), node.totalOperations(), node.runningOperations(),
                    node.machineLabel(), node.reason(), node.technicalDetail(), node.draining(), inputs, truncated, cycle);
        } catch (IllegalArgumentException invalid) {
            throw new DecoderException("invalid node monitor data", invalid);
        }
    }

    /** 保留旧的可选数量尾部；未知产量的 0/0 占位只在附带计时时有效。 */
    static void writeProgressTail(FriendlyByteBuf buf, CraftOutputProgress output, CraftProgressTiming timing) {
        if (!timing.known()) {
            writeOutputProgress(buf, output);
            return;
        }
        buf.writeVarLong(output.known() ? output.completed() : 0L);
        buf.writeVarLong(output.known() ? output.total() : 0L);
        buf.writeVarInt(1);
        buf.writeVarLong(timing.elapsedMillis());
        buf.writeVarLong(timing.sinceProgressMillis());
    }

    static ProgressTail readProgressTail(FriendlyByteBuf buf) {
        if (!buf.isReadable()) return new ProgressTail(CraftOutputProgress.UNKNOWN, CraftProgressTiming.UNKNOWN);
        long completed = buf.readVarLong();
        long total = buf.readVarLong();
        boolean timingOnly = completed == 0L && total == 0L && buf.isReadable();
        if (!timingOnly && (completed < 0L || total <= 0L || completed > total)) {
            throw new DecoderException("output quantity counts out of bounds");
        }
        CraftOutputProgress output = timingOnly ? CraftOutputProgress.UNKNOWN : new CraftOutputProgress(completed, total);
        if (!buf.isReadable()) return new ProgressTail(output, CraftProgressTiming.UNKNOWN);
        if (buf.readVarInt() != 1) throw new DecoderException("unsupported progress timing version");
        long elapsed = buf.readVarLong();
        long idle = buf.readVarLong();
        if (elapsed < 0L || idle < 0L || idle > elapsed) throw new DecoderException("craft timing out of bounds");
        if (buf.isReadable()) throw new DecoderException("unexpected progress timing data");
        return new ProgressTail(output, new CraftProgressTiming(elapsed, idle));
    }

    record ProgressTail(CraftOutputProgress output, CraftProgressTiming timing) {}

    /** 可选尾部字段；没有数量字段的旧进度包仍按原有步骤/操作进度处理。 */
    static void writeOutputProgress(FriendlyByteBuf buf, CraftOutputProgress progress) {
        if (!progress.known()) return;
        buf.writeVarLong(progress.completed());
        buf.writeVarLong(progress.total());
    }

    static CraftOutputProgress readOutputProgress(FriendlyByteBuf buf) {
        if (!buf.isReadable()) return CraftOutputProgress.UNKNOWN;
        long completed = buf.readVarLong();
        long total = buf.readVarLong();
        if (completed < 0L || total <= 0L || completed > total) {
            throw new DecoderException("output quantity counts out of bounds");
        }
        return new CraftOutputProgress(completed, total);
    }

    private static int readBoundedCount(FriendlyByteBuf buf) {
        int count = buf.readVarInt();
        if (count < 0 || count > MAX_NODES) {
            throw new DecoderException("progress node count out of bounds: " + count);
        }
        return count;
    }

    private static int readNonNegative(FriendlyByteBuf buf, String field) {
        int value = buf.readVarInt();
        if (value < 0) throw new DecoderException(field + " must be non-negative: " + value);
        return value;
    }

    private static void validateOperationCountsForEncode(int completed, int total, int running) {
        if (completed > total || running > total - completed) {
            throw new IllegalArgumentException("operation counts out of bounds: completed=" + completed
                    + ", running=" + running + ", total=" + total);
        }
    }

    private static void validateOperationCounts(int completed, int total, int running) {
        if (completed > total || running > total - completed) {
            throw new DecoderException("operation counts out of bounds: completed=" + completed
                    + ", running=" + running + ", total=" + total);
        }
    }

    private static int requireNonNegative(int value, String field) {
        if (value < 0) throw new IllegalArgumentException(field + " must be non-negative: " + value);
        return value;
    }

    public static void handle(CraftProgressPacket packet, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> CraftProgressClientPacketHandler.onProgress(packet.snapshot)));
        ctx.get().setPacketHandled(true);
    }

    public CraftProgressSnapshot snapshot() { return snapshot; }
}
