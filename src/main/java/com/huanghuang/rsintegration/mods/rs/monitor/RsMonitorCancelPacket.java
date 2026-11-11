package com.huanghuang.rsintegration.mods.rs.monitor;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

public record RsMonitorCancelPacket(int containerId, UUID session, UUID craftId) {
    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(containerId);
        buf.writeUUID(session);
        buf.writeUUID(craftId);
    }

    public static RsMonitorCancelPacket decode(FriendlyByteBuf buf) {
        return new RsMonitorCancelPacket(buf.readVarInt(), buf.readUUID(), buf.readUUID());
    }

    public static void handle(RsMonitorCancelPacket packet, Supplier<NetworkEvent.Context> supplier) {
        var context = supplier.get();
        context.enqueueWork(() -> {
            if (context.getSender() != null) RsMonitorServer.cancel(context.getSender(), packet);
        });
        context.setPacketHandled(true);
    }
}
