package com.huanghuang.rsintegration.autoeat.network;

import com.huanghuang.rsintegration.autoeat.AutoEatMode;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;

public class AutoEatSyncPacket {
    public final AutoEatMode mode;
    public final int count;
    public final Component message;

    public AutoEatSyncPacket(AutoEatMode mode, int count, Component message) {
        this.mode = mode;
        this.count = count;
        this.message = message;
    }

    public static void encode(AutoEatSyncPacket packet, FriendlyByteBuf buf) {
        buf.writeEnum(packet.mode);
        buf.writeVarInt(packet.count);
        buf.writeComponent(packet.message);
    }

    public static AutoEatSyncPacket decode(FriendlyByteBuf buf) {
        return new AutoEatSyncPacket(
                buf.readEnum(AutoEatMode.class),
                buf.readVarInt(),
                buf.readComponent()
        );
    }

    public static void handle(AutoEatSyncPacket packet, java.util.function.Supplier<net.minecraftforge.network.NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            var player = net.minecraft.client.Minecraft.getInstance().player;
            if (player != null) {
                player.displayClientMessage(packet.message, true);
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
