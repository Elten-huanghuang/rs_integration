package com.huanghuang.rsintegration.autoeat.network;

import com.huanghuang.rsintegration.autoeat.AutoEatEngine;
import com.huanghuang.rsintegration.autoeat.AutoEatBlacklistPolicy;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Supplier;

public class UpdateBlacklistPacket {
    public final Set<ResourceLocation> added;
    public final Set<ResourceLocation> removed;
    public final Set<ResourceLocation> addedEffects;
    public final Set<ResourceLocation> removedEffects;
    public final boolean snapshot;

    public UpdateBlacklistPacket(Set<ResourceLocation> added, Set<ResourceLocation> removed,
                                 Set<ResourceLocation> addedEffects, Set<ResourceLocation> removedEffects) {
        this(added, removed, addedEffects, removedEffects, false);
    }

    public UpdateBlacklistPacket(Set<ResourceLocation> items, Set<ResourceLocation> effects) {
        this(items, Set.of(), effects, Set.of(), true);
    }

    private UpdateBlacklistPacket(Set<ResourceLocation> added, Set<ResourceLocation> removed,
                                  Set<ResourceLocation> addedEffects, Set<ResourceLocation> removedEffects,
                                  boolean snapshot) {
        this.added = added;
        this.removed = removed;
        this.addedEffects = addedEffects;
        this.removedEffects = removedEffects;
        this.snapshot = snapshot;
    }

    public static void encode(UpdateBlacklistPacket packet, FriendlyByteBuf buf) {
        writeSet(buf, packet.added);
        writeSet(buf, packet.removed);
        writeSet(buf, packet.addedEffects);
        writeSet(buf, packet.removedEffects);
        buf.writeBoolean(packet.snapshot);
    }

    public static UpdateBlacklistPacket decode(FriendlyByteBuf buf) {
        return new UpdateBlacklistPacket(readSet(buf), readSet(buf), readSet(buf), readSet(buf), buf.readBoolean());
    }

    public static void handle(UpdateBlacklistPacket packet, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            var sender = ctx.get().getSender();
            if (sender != null && !(sender instanceof FakePlayer)) {
                if (packet.snapshot) {
                    AutoEatEngine.replaceBlacklists(sender, packet.added, packet.addedEffects);
                } else {
                    AutoEatEngine.updateBlacklist(sender, packet.added, packet.removed);
                    AutoEatEngine.updateEffectBlacklist(sender, packet.addedEffects, packet.removedEffects);
                }
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
        if (size < 0 || size > AutoEatBlacklistPolicy.MAX_SIZE) {
            throw new DecoderException("blacklist size out of range: " + size);
        }
        Set<ResourceLocation> set = new HashSet<>(Math.min(size, 256));
        for (int i = 0; i < size; i++) {
            set.add(buf.readResourceLocation());
        }
        return set;
    }
}
