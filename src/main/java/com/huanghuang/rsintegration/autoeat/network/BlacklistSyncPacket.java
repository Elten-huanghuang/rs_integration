package com.huanghuang.rsintegration.autoeat.network;

import com.huanghuang.rsintegration.autoeat.client.ClientState;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

import java.util.HashSet;
import java.util.Set;

public class BlacklistSyncPacket {
    public final Set<ResourceLocation> blacklist;
    public final Set<ResourceLocation> effectBlacklist;

    public BlacklistSyncPacket(Set<ResourceLocation> blacklist, Set<ResourceLocation> effectBlacklist) {
        this.blacklist = blacklist;
        this.effectBlacklist = effectBlacklist;
    }

    public static void encode(BlacklistSyncPacket packet, FriendlyByteBuf buf) {
        writeSet(buf, packet.blacklist);
        writeSet(buf, packet.effectBlacklist);
    }

    public static BlacklistSyncPacket decode(FriendlyByteBuf buf) {
        return new BlacklistSyncPacket(readSet(buf), readSet(buf));
    }

    public static void handle(BlacklistSyncPacket packet, java.util.function.Supplier<net.minecraftforge.network.NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ClientState.blacklistedItems.clear();
            ClientState.blacklistedItems.addAll(packet.blacklist);
            ClientState.blacklistedEffects.clear();
            ClientState.blacklistedEffects.addAll(packet.effectBlacklist);
        });
        ctx.get().setPacketHandled(true);
    }

    private static void writeSet(FriendlyByteBuf buf, Set<ResourceLocation> set) {
        buf.writeVarInt(set.size());
        for (ResourceLocation rl : set) buf.writeResourceLocation(rl);
    }

    private static Set<ResourceLocation> readSet(FriendlyByteBuf buf) {
        int size = buf.readVarInt();
        if (size < 0 || size > 4096) {
            throw new io.netty.handler.codec.DecoderException("effect blacklist size out of range: " + size);
        }
        Set<ResourceLocation> set = new HashSet<>(Math.min(size, 256));
        for (int i = 0; i < size; i++) set.add(buf.readResourceLocation());
        return set;
    }
}
