package com.huanghuang.rsintegration.autoeat.network;

import com.huanghuang.rsintegration.autoeat.client.ClientState;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

import java.util.HashSet;
import java.util.Set;

public class BlacklistSyncPacket {
    public final Set<ResourceLocation> blacklist;

    public BlacklistSyncPacket(Set<ResourceLocation> blacklist) {
        this.blacklist = blacklist;
    }

    public static void encode(BlacklistSyncPacket packet, FriendlyByteBuf buf) {
        buf.writeVarInt(packet.blacklist.size());
        for (ResourceLocation rl : packet.blacklist) {
            buf.writeResourceLocation(rl);
        }
    }

    public static BlacklistSyncPacket decode(FriendlyByteBuf buf) {
        int size = buf.readVarInt();
        Set<ResourceLocation> set = new HashSet<>(size);
        for (int i = 0; i < size; i++) {
            set.add(buf.readResourceLocation());
        }
        return new BlacklistSyncPacket(set);
    }

    public static void handle(BlacklistSyncPacket packet, java.util.function.Supplier<net.minecraftforge.network.NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ClientState.blacklistedItems.clear();
            ClientState.blacklistedItems.addAll(packet.blacklist);
        });
        ctx.get().setPacketHandled(true);
    }
}
