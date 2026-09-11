package com.huanghuang.rsintegration.autoeat.network;

import com.huanghuang.rsintegration.autoeat.AutoEatMode;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;

import java.util.HashSet;
import java.util.Collection;
import java.util.List;
import java.util.Set;

public class BlacklistSyncPacket {
    public final Set<ResourceLocation> blacklist;
    public final Set<ResourceLocation> effectBlacklist;
    public final AutoEatMode mode;
    public final List<ResourceLocation> selectedItems;

    public BlacklistSyncPacket(Set<ResourceLocation> blacklist, Set<ResourceLocation> effectBlacklist) {
        this(blacklist, effectBlacklist, AutoEatMode.DIVERSITY, null);
    }

    public BlacklistSyncPacket(Set<ResourceLocation> blacklist, Set<ResourceLocation> effectBlacklist,
                               AutoEatMode mode, Collection<ResourceLocation> selectedItems) {
        this.blacklist = blacklist;
        this.effectBlacklist = effectBlacklist;
        this.mode = mode;
        this.selectedItems = AutoEatSelectionCodec.copy(selectedItems);
    }

    public static void encode(BlacklistSyncPacket packet, FriendlyByteBuf buf) {
        writeSet(buf, packet.blacklist);
        writeSet(buf, packet.effectBlacklist);
        buf.writeEnum(packet.mode);
        AutoEatSelectionCodec.write(buf, packet.selectedItems);
    }

    public static BlacklistSyncPacket decode(FriendlyByteBuf buf) {
        Set<ResourceLocation> blacklist = readSet(buf);
        Set<ResourceLocation> effectBlacklist = readSet(buf);
        AutoEatMode mode = buf.readEnum(AutoEatMode.class);
        return new BlacklistSyncPacket(blacklist, effectBlacklist, mode,
                AutoEatSelectionCodec.read(buf));
    }

    public static void handle(BlacklistSyncPacket packet, java.util.function.Supplier<net.minecraftforge.network.NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> AutoEatClientPacketHandler.onBlacklistSync(packet)));
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
