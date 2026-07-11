package com.huanghuang.rsintegration.autoeat.network;

import com.huanghuang.rsintegration.autoeat.AutoEatEngine;
import com.huanghuang.rsintegration.network.packet.NetworkHandler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.PacketDistributor;

import java.util.Set;

public class RequestBlacklistPacket {

    public RequestBlacklistPacket() {}

    public static void encode(RequestBlacklistPacket packet, FriendlyByteBuf buf) {}

    public static RequestBlacklistPacket decode(FriendlyByteBuf buf) {
        return new RequestBlacklistPacket();
    }

    public static void handle(RequestBlacklistPacket packet, java.util.function.Supplier<net.minecraftforge.network.NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            var sender = ctx.get().getSender();
            if (sender != null && !(sender instanceof net.minecraftforge.common.util.FakePlayer)) {
                Set<ResourceLocation> blacklist = AutoEatEngine.getBlacklist(sender);
                NetworkHandler.CHANNEL.send(PacketDistributor.PLAYER.with(() -> sender),
                        new BlacklistSyncPacket(blacklist));
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
