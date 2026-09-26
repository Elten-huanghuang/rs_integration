package com.huanghuang.rsintegration.autoeat.network;

import com.huanghuang.rsintegration.autoeat.AutoEatEngine;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class AutoEatStopPacket {

    public static void encode(AutoEatStopPacket packet, FriendlyByteBuf buf) {}

    public static AutoEatStopPacket decode(FriendlyByteBuf buf) {
        return new AutoEatStopPacket();
    }

    public static void handle(AutoEatStopPacket packet, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            var sender = ctx.get().getSender();
            if (sender != null && !(sender instanceof FakePlayer)) {
                AutoEatEngine.stop(sender);
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
