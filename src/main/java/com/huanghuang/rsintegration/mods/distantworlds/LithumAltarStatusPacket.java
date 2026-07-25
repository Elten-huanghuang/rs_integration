package com.huanghuang.rsintegration.mods.distantworlds;

import com.huanghuang.rsintegration.mods.distantworlds.client.LithumAltarStatusCache;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
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

    /**
     * Must NOT be {@code @OnlyIn(Dist.CLIENT)}: Forge strips such methods from the
     * class on a dedicated server, while {@code registerNetworkPackets} references
     * this as a method ref during {@code common_setup} — the JVM then raises
     * {@link NoSuchMethodError} and mod loading fails outright. The method stays on
     * both sides; the client-only cache update is deferred behind DistExecutor so
     * {@link LithumAltarStatusCache} is never linked on the server.
     */
    public static void handle(LithumAltarStatusPacket packet, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> net.minecraftforge.fml.DistExecutor.unsafeRunWhenOn(
                Dist.CLIENT, () -> () -> LithumAltarStatusCache.update(packet.snapshot)));
        ctx.get().setPacketHandled(true);
    }
}
