package com.huanghuang.rsintegration.autoeat.network;

import com.huanghuang.rsintegration.autoeat.AutoEatEngine;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

import java.util.HashSet;
import java.util.Set;

public class UpdateBlacklistPacket {
    public final Set<ResourceLocation> added;
    public final Set<ResourceLocation> removed;

    public UpdateBlacklistPacket(Set<ResourceLocation> added, Set<ResourceLocation> removed) {
        this.added = added;
        this.removed = removed;
    }

    public static void encode(UpdateBlacklistPacket packet, FriendlyByteBuf buf) {
        writeSet(buf, packet.added);
        writeSet(buf, packet.removed);
    }

    public static UpdateBlacklistPacket decode(FriendlyByteBuf buf) {
        return new UpdateBlacklistPacket(readSet(buf), readSet(buf));
    }

    public static void handle(UpdateBlacklistPacket packet, java.util.function.Supplier<net.minecraftforge.network.NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            var sender = ctx.get().getSender();
            if (sender != null && !(sender instanceof net.minecraftforge.common.util.FakePlayer)) {
                AutoEatEngine.updateBlacklist(sender, packet.added, packet.removed);
            }
        });
        ctx.get().setPacketHandled(true);
    }

    private static void writeSet(FriendlyByteBuf buf, Set<ResourceLocation> set) {
        buf.writeVarInt(set.size());
        for (ResourceLocation rl : set) {
            buf.writeResourceLocation(rl);
        }
    }

    private static Set<ResourceLocation> readSet(FriendlyByteBuf buf) {
        int size = buf.readVarInt();
        if (size < 0 || size > 4096) {
            throw new io.netty.handler.codec.DecoderException("blacklist size out of range: " + size);
        }
        Set<ResourceLocation> set = new HashSet<>(Math.min(size, 256));
        for (int i = 0; i < size; i++) {
            set.add(buf.readResourceLocation());
        }
        return set;
    }
}
