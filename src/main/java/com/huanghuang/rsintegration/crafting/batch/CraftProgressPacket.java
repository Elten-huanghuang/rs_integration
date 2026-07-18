package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.crafting.CraftProgressSnapshot;
import com.huanghuang.rsintegration.crafting.CraftProgressTracker;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/** S2C progress update for one running or terminal craft. */
public final class CraftProgressPacket {

    static final int MAX_NODES = 4096;
    static final int MAX_TECHNICAL_DETAIL_LENGTH = 1024;
    static final int MAX_RECIPE_ID_LENGTH = 256;
    static final int MAX_MOD_TYPE_ID_LENGTH = 128;
    static final int MAX_MACHINE_LABEL_LENGTH = 256;

    private final CraftProgressSnapshot snapshot;

    public CraftProgressPacket(CraftProgressSnapshot snapshot) {
        this.snapshot = snapshot;
    }

    public void encode(FriendlyByteBuf buf) {
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
            buf.writeItem(node.displayOutput());
            buf.writeVarInt(completedOperations);
            buf.writeVarInt(totalOperations);
            buf.writeVarInt(runningOperations);
            buf.writeUtf(node.machineLabel(), MAX_MACHINE_LABEL_LENGTH);
            buf.writeVarInt(node.reason().ordinal());
            buf.writeUtf(node.technicalDetail(), MAX_TECHNICAL_DETAIL_LENGTH);
            buf.writeBoolean(node.draining());
        }
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
            net.minecraft.world.item.ItemStack displayOutput = buf.readItem();
            int completedOperations = readNonNegative(buf, "completedOperations");
            int totalOperations = readNonNegative(buf, "totalOperations");
            int runningOperations = readNonNegative(buf, "runningOperations");
            validateOperationCounts(completedOperations, totalOperations, runningOperations);
            String machineLabel = buf.readUtf(MAX_MACHINE_LABEL_LENGTH);
            CraftProgressSnapshot.Reason nodeReason =
                    CraftProgressSnapshot.Reason.fromOrdinal(buf.readVarInt());
            String nodeDetail = buf.readUtf(MAX_TECHNICAL_DETAIL_LENGTH);
            boolean draining = buf.readBoolean();
            nodes.add(new CraftProgressSnapshot.NodeProgress(nodeId, nodeState,
                    recipeId, modTypeId, displayOutput, completedOperations, totalOperations,
                    runningOperations, machineLabel, nodeReason, nodeDetail, draining));
        }
        return new CraftProgressPacket(new CraftProgressSnapshot(craftId, sequence, result, reason,
                completed, total, running, detail, List.copyOf(nodes)));
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
        ctx.get().enqueueWork(() -> CraftProgressTracker.onProgress(packet.snapshot));
        ctx.get().setPacketHandled(true);
    }

    public CraftProgressSnapshot snapshot() { return snapshot; }
}
