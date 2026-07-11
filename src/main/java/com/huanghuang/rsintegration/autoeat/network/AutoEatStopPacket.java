package com.huanghuang.rsintegration.autoeat.network;

import net.minecraft.network.FriendlyByteBuf;

public class AutoEatStopPacket {

    public static void encode(AutoEatStopPacket packet, FriendlyByteBuf buf) {}

    public static AutoEatStopPacket decode(FriendlyByteBuf buf) {
        return new AutoEatStopPacket();
    }

    public static void handle(AutoEatStopPacket packet, java.util.function.Supplier<net.minecraftforge.network.NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            var sender = ctx.get().getSender();
            if (sender != null && !(sender instanceof net.minecraftforge.common.util.FakePlayer)) {
                com.huanghuang.rsintegration.autoeat.AutoEatEngine.stop(sender);
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
