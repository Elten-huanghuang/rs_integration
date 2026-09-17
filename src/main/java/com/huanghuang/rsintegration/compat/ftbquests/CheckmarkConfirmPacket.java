package com.huanghuang.rsintegration.compat.ftbquests;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Requests one server-authoritative pass over currently available checkmark tasks. */
public final class CheckmarkConfirmPacket {

    public static void encode(CheckmarkConfirmPacket packet, FriendlyByteBuf buffer) {
    }

    public static CheckmarkConfirmPacket decode(FriendlyByteBuf buffer) {
        return new CheckmarkConfirmPacket();
    }

    public static void handle(CheckmarkConfirmPacket packet,
                              Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            if (context.getSender() != null) {
                CheckmarkConfirmService.confirmAvailable(context.getSender());
            }
        });
        context.setPacketHandled(true);
    }
}
