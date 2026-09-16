package com.huanghuang.rsintegration.network.packet;

import com.huanghuang.rsintegration.server.JeiNetworkInventorySyncManager;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Client request used only after an epoch/sequence discontinuity. */
public record JeiNetworkInventoryResyncRequestPacket(long epoch, long sequence) {
    public JeiNetworkInventoryResyncRequestPacket {
        if (epoch < 0 || sequence < 0) throw new IllegalArgumentException("negative JEI resync version");
    }

    public static void encode(JeiNetworkInventoryResyncRequestPacket packet, FriendlyByteBuf buf) {
        buf.writeVarLong(packet.epoch);
        buf.writeVarLong(packet.sequence);
    }

    public static JeiNetworkInventoryResyncRequestPacket decode(FriendlyByteBuf buf) {
        long epoch = buf.readVarLong();
        long sequence = buf.readVarLong();
        if (epoch < 0 || sequence < 0) throw new DecoderException("negative JEI resync version");
        return new JeiNetworkInventoryResyncRequestPacket(epoch, sequence);
    }

    public static void handle(JeiNetworkInventoryResyncRequestPacket packet,
                              Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context ctx = supplier.get();
        ServerPlayer player = ctx.getSender();
        if (player != null) ctx.enqueueWork(() ->
                JeiNetworkInventorySyncManager.requestResync(player, packet.epoch, packet.sequence));
        ctx.setPacketHandled(true);
    }
}
