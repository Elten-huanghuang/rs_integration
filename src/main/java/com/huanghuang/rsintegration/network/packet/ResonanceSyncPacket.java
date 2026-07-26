package com.huanghuang.rsintegration.network.packet;

import com.huanghuang.rsintegration.resonance.bridge.ClientDiskData;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Server→Client: syncs resonance disk gem count so the Avarice Ring
 * tooltip can show the correct damage boost including disk gems.
 */
public class ResonanceSyncPacket {

    public final int diskGems;
    public final int lycheeCatalystMask;
    public final long revision;

    public ResonanceSyncPacket(int diskGems) {
        this(diskGems, 0, 0L);
    }

    public ResonanceSyncPacket(int diskGems, int lycheeCatalystMask, long revision) {
        this.diskGems = diskGems;
        this.lycheeCatalystMask = lycheeCatalystMask;
        this.revision = revision;
    }

    public static void encode(ResonanceSyncPacket packet, FriendlyByteBuf buf) {
        buf.writeVarInt(packet.diskGems);
        buf.writeVarInt(packet.lycheeCatalystMask);
        buf.writeVarLong(packet.revision);
    }

    public static ResonanceSyncPacket decode(FriendlyByteBuf buf) {
        return new ResonanceSyncPacket(buf.readVarInt(), buf.readVarInt(), buf.readVarLong());
    }

    public static void handle(ResonanceSyncPacket packet, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> ClientDiskData.apply(
                packet.diskGems, packet.lycheeCatalystMask, packet.revision));
        ctx.get().setPacketHandled(true);
    }
}
