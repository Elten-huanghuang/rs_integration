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

    public ResonanceSyncPacket(int diskGems) {
        this.diskGems = diskGems;
    }

    public static void encode(ResonanceSyncPacket packet, FriendlyByteBuf buf) {
        buf.writeVarInt(packet.diskGems);
    }

    public static ResonanceSyncPacket decode(FriendlyByteBuf buf) {
        return new ResonanceSyncPacket(buf.readVarInt());
    }

    public static void handle(ResonanceSyncPacket packet, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> ClientDiskData.setGemCount(packet.diskGems));
        ctx.get().setPacketHandled(true);
    }
}
