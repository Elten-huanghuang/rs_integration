package com.huanghuang.rsintegration.autoeat.network;

import com.huanghuang.rsintegration.autoeat.AutoEatEngine;
import com.huanghuang.rsintegration.autoeat.AutoEatPreferences;
import com.huanghuang.rsintegration.network.packet.NetworkHandler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.Set;
import java.util.function.Supplier;

public class RequestBlacklistPacket {

    public RequestBlacklistPacket() {}

    public static void encode(RequestBlacklistPacket packet, FriendlyByteBuf buf) {}

    public static RequestBlacklistPacket decode(FriendlyByteBuf buf) {
        return new RequestBlacklistPacket();
    }

    public static void handle(RequestBlacklistPacket packet, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            var sender = ctx.get().getSender();
            if (sender != null && !(sender instanceof FakePlayer)) {
                Set<ResourceLocation> blacklist = AutoEatEngine.getBlacklist(sender);
                AutoEatPreferences preferences = AutoEatPreferences.load(sender);
                NetworkHandler.CHANNEL.send(PacketDistributor.PLAYER.with(() -> sender),
                        new BlacklistSyncPacket(blacklist, AutoEatEngine.getEffectBlacklist(sender),
                                preferences.mode(), preferences.selectedItems()));
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
