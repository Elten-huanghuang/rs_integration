package com.huanghuang.rsintegration.machine;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Client request to collect a BD-bound furnace output. */
public final class BeyondDimensionsMachineCollectPacket {
    private final ResourceLocation dim;
    private final BlockPos pos;
    private final boolean toNetwork;

    public BeyondDimensionsMachineCollectPacket(ResourceLocation dim, BlockPos pos, boolean toNetwork) {
        this.dim = dim;
        this.pos = pos;
        this.toNetwork = toNetwork;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeResourceLocation(dim);
        buf.writeBlockPos(pos);
        buf.writeBoolean(toNetwork);
    }

    public static BeyondDimensionsMachineCollectPacket decode(FriendlyByteBuf buf) {
        return new BeyondDimensionsMachineCollectPacket(
                buf.readResourceLocation(), buf.readBlockPos(), buf.readBoolean());
    }

    public static void handle(BeyondDimensionsMachineCollectPacket packet,
                              Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        ServerPlayer player = context.getSender();
        if (player != null) {
            context.enqueueWork(() -> BeyondDimensionsMachineOperations.collect(
                    player, packet.dim, packet.pos, packet.toNetwork));
        }
        context.setPacketHandled(true);
    }
}
