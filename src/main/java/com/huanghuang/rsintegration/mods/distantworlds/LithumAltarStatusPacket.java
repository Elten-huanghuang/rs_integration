package com.huanghuang.rsintegration.mods.distantworlds;

import com.huanghuang.rsintegration.mods.distantworlds.client.LithumAltarStatusCache;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public final class LithumAltarStatusPacket {
    private final LithumAltarStatusSnapshot snapshot;

    public LithumAltarStatusPacket(LithumAltarStatusSnapshot snapshot) {
        this.snapshot = snapshot;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeResourceLocation(snapshot.dimension());
        buf.writeBlockPos(snapshot.pos());
        buf.writeUtf(snapshot.currentRecipe(), 128);
        buf.writeDouble(snapshot.currentEnergy());
        buf.writeDouble(snapshot.maxEnergy());
        buf.writeDouble(snapshot.recovery());
    }

    public static LithumAltarStatusPacket decode(FriendlyByteBuf buf) {
        return new LithumAltarStatusPacket(new LithumAltarStatusSnapshot(
                buf.readResourceLocation(), buf.readBlockPos(), buf.readUtf(128),
                buf.readDouble(), buf.readDouble(), buf.readDouble()));
    }

    @OnlyIn(Dist.CLIENT)
    public static void handle(LithumAltarStatusPacket packet, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> LithumAltarStatusCache.update(packet.snapshot));
        ctx.get().setPacketHandled(true);
    }
}
