package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.crafting.CraftProgressSnapshot;
import com.huanghuang.rsintegration.command.PerformanceMonitor;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/** S2C changes to an already-known craft progress snapshot. */
public final class CraftProgressDeltaPacket {
    private static final int MAX_NODES = CraftProgressPacket.MAX_NODES;
    private static final int MAX_DETAIL = 1024;

    private final UUID craftId;
    private final int baseSequence;
    private final int sequence;
    private final CraftProgressSnapshot.Result result;
    private final CraftProgressSnapshot.Reason reason;
    private final int completedNodes;
    private final int totalNodes;
    private final int runningNodes;
    private final String technicalDetail;
    private final List<CraftProgressSnapshot.NodeProgress> changedNodes;

    public CraftProgressDeltaPacket(UUID craftId, int baseSequence, CraftProgressSnapshot snapshot,
                                    List<CraftProgressSnapshot.NodeProgress> changedNodes) {
        this.craftId = craftId;
        this.baseSequence = baseSequence;
        this.sequence = snapshot.sequence();
        this.result = snapshot.result();
        this.reason = snapshot.reason();
        this.completedNodes = snapshot.completedNodes();
        this.totalNodes = snapshot.totalNodes();
        this.runningNodes = snapshot.runningNodes();
        this.technicalDetail = snapshot.technicalDetail();
        this.changedNodes = List.copyOf(changedNodes);
    }

    public void encode(FriendlyByteBuf buf) {
        int startIndex = buf.writerIndex();
        buf.writeUUID(craftId);
        buf.writeVarInt(baseSequence);
        buf.writeVarInt(sequence);
        buf.writeVarInt(result.ordinal());
        buf.writeVarInt(reason.ordinal());
        buf.writeVarInt(completedNodes);
        buf.writeVarInt(totalNodes);
        buf.writeVarInt(runningNodes);
        buf.writeBoolean(technicalDetail != null);
        if (technicalDetail != null) buf.writeUtf(technicalDetail, MAX_DETAIL);
        if (changedNodes.size() > MAX_NODES) throw new IllegalArgumentException("too many delta nodes");
        buf.writeVarInt(changedNodes.size());
        for (CraftProgressSnapshot.NodeProgress node : changedNodes) {
            buf.writeVarInt(node.nodeId());
            buf.writeVarInt(node.state().ordinal());
            buf.writeUtf(node.recipeId(), 256);
            buf.writeUtf(node.modTypeId(), 128);
            buf.writeItem(node.displayOutput());
            buf.writeVarInt(node.completedOperations());
            buf.writeVarInt(node.totalOperations());
            buf.writeVarInt(node.runningOperations());
            buf.writeUtf(node.machineLabel(), 256);
            buf.writeVarInt(node.reason().ordinal());
            buf.writeUtf(node.technicalDetail(), MAX_DETAIL);
            buf.writeBoolean(node.draining());
        }
        PerformanceMonitor.recordProgressPacketBytes(buf.writerIndex() - startIndex);
    }

    public static CraftProgressDeltaPacket decode(FriendlyByteBuf buf) {
        UUID id = buf.readUUID();
        int base = buf.readVarInt();
        int sequence = buf.readVarInt();
        CraftProgressSnapshot.Result result = CraftProgressSnapshot.Result.fromOrdinal(buf.readVarInt());
        CraftProgressSnapshot.Reason reason = CraftProgressSnapshot.Reason.fromOrdinal(buf.readVarInt());
        int completed = nonNegative(buf, "completedNodes");
        int total = nonNegative(buf, "totalNodes");
        int running = nonNegative(buf, "runningNodes");
        String detail = buf.readBoolean() ? buf.readUtf(MAX_DETAIL) : null;
        int count = buf.readVarInt();
        if (count < 0 || count > MAX_NODES) throw new DecoderException("delta node count out of bounds");
        List<CraftProgressSnapshot.NodeProgress> nodes = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int nodeId = nonNegative(buf, "nodeId");
            var state = CraftProgressSnapshot.NodeState.fromOrdinal(buf.readVarInt());
            String recipe = buf.readUtf(256);
            String mod = buf.readUtf(128);
            var output = buf.readItem();
            int completedOps = nonNegative(buf, "completedOperations");
            int totalOps = nonNegative(buf, "totalOperations");
            int runningOps = nonNegative(buf, "runningOperations");
            String machine = buf.readUtf(256);
            var nodeReason = CraftProgressSnapshot.Reason.fromOrdinal(buf.readVarInt());
            String nodeDetail = buf.readUtf(MAX_DETAIL);
            boolean draining = buf.readBoolean();
            nodes.add(new CraftProgressSnapshot.NodeProgress(nodeId, state, recipe, mod, output,
                    completedOps, totalOps, runningOps, machine, nodeReason, nodeDetail, draining));
        }
        var snapshot = new CraftProgressSnapshot(id, sequence, result, reason, completed, total,
                running, detail, nodes);
        return new CraftProgressDeltaPacket(id, base, snapshot, nodes);
    }

    private static int nonNegative(FriendlyByteBuf buf, String name) {
        int value = buf.readVarInt();
        if (value < 0) throw new DecoderException(name + " must be non-negative");
        return value;
    }

    public static void handle(CraftProgressDeltaPacket packet, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> CraftProgressClientPacketHandler.onDelta(packet)));
        ctx.get().setPacketHandled(true);
    }

    public UUID craftId() { return craftId; }
    public int baseSequence() { return baseSequence; }
    public int sequence() { return sequence; }
    public CraftProgressSnapshot.Result result() { return result; }
    public CraftProgressSnapshot.Reason reason() { return reason; }
    public int completedNodes() { return completedNodes; }
    public int totalNodes() { return totalNodes; }
    public int runningNodes() { return runningNodes; }
    public String technicalDetail() { return technicalDetail; }
    public List<CraftProgressSnapshot.NodeProgress> changedNodes() { return changedNodes; }
}
