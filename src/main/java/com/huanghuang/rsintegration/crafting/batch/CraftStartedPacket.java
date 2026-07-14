package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.crafting.CraftProgressTracker;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * S2C: server confirms a craft has been accepted and is about to begin executing.
 * Carries the stable craftId so the client can correlate progress updates.
 */
public final class CraftStartedPacket {

    private final UUID craftId;
    private final int totalNodes;
    private final boolean graphMode;

    public CraftStartedPacket(UUID craftId, int totalNodes, boolean graphMode) {
        this.craftId = craftId;
        this.totalNodes = totalNodes;
        this.graphMode = graphMode;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUUID(craftId);
        buf.writeVarInt(totalNodes);
        buf.writeBoolean(graphMode);
    }

    public static CraftStartedPacket decode(FriendlyByteBuf buf) {
        return new CraftStartedPacket(buf.readUUID(), buf.readVarInt(), buf.readBoolean());
    }

    @OnlyIn(Dist.CLIENT)
    public static void handle(CraftStartedPacket packet, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> CraftProgressTracker.onStarted(packet));
        ctx.get().setPacketHandled(true);
    }

    public UUID craftId() { return craftId; }
    public int totalNodes() { return totalNodes; }
    public boolean graphMode() { return graphMode; }
}
