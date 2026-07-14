package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.crafting.CraftProgressSnapshot;
import com.huanghuang.rsintegration.crafting.CraftProgressTracker;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * S2C: periodic progress update for a running craft.
 * Terminal state is indicated by sequence == {@link CraftProgressSnapshot#TERMINAL_SEQUENCE}.
 */
public final class CraftProgressPacket {

    private final CraftProgressSnapshot snapshot;

    public CraftProgressPacket(CraftProgressSnapshot snapshot) {
        this.snapshot = snapshot;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUUID(snapshot.craftId());
        buf.writeVarInt(snapshot.sequence());
        buf.writeByte(snapshot.chainState());
        buf.writeVarInt(snapshot.completedNodes());
        buf.writeVarInt(snapshot.totalNodes());
        buf.writeVarInt(snapshot.runningNodes());
        buf.writeBoolean(snapshot.failedStep() != null);
        if (snapshot.failedStep() != null) buf.writeUtf(snapshot.failedStep());
    }

    public static CraftProgressPacket decode(FriendlyByteBuf buf) {
        UUID craftId = buf.readUUID();
        int sequence = buf.readVarInt();
        byte state = buf.readByte();
        int completed = buf.readVarInt();
        int total = buf.readVarInt();
        int running = buf.readVarInt();
        String failed = buf.readBoolean() ? buf.readUtf() : null;
        return new CraftProgressPacket(
                new CraftProgressSnapshot(craftId, sequence, state, completed, total, running, failed));
    }

    @OnlyIn(Dist.CLIENT)
    public static void handle(CraftProgressPacket packet, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> CraftProgressTracker.onProgress(packet.snapshot));
        ctx.get().setPacketHandled(true);
    }

    public CraftProgressSnapshot snapshot() { return snapshot; }
}
