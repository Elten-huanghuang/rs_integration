package com.huanghuang.rsintegration.crafting.planning;

import io.netty.handler.codec.DecoderException;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Server-to-client update for one planning preview request. */
public final class PlanningProgressPacket {
    private final PlanningProgressSnapshot snapshot;

    public PlanningProgressPacket(PlanningProgressSnapshot snapshot) {
        this.snapshot = snapshot;
    }

    public static void encode(PlanningProgressPacket packet, FriendlyByteBuf buf) {
        PlanningProgressSnapshot snapshot = packet.snapshot;
        buf.writeVarLong(snapshot.requestId());
        buf.writeVarLong(snapshot.requestGeneration());
        buf.writeResourceLocation(snapshot.recipeId());
        buf.writeVarInt(snapshot.state().ordinal());
        buf.writeVarInt(snapshot.phase().ordinal());
        buf.writeVarLong(snapshot.elapsedMillis());
        buf.writeComponent(snapshot.detail());
    }

    public static PlanningProgressPacket decode(FriendlyByteBuf buf) {
        long requestId = buf.readVarLong();
        long generation = buf.readVarLong();
        if (requestId <= 0L || generation < 0L) {
            throw new DecoderException("Invalid planning progress correlation");
        }
        var recipeId = buf.readResourceLocation();
        int stateOrdinal = buf.readVarInt();
        int phaseOrdinal = buf.readVarInt();
        if (stateOrdinal < 0 || stateOrdinal >= PlanningProgressSnapshot.State.values().length
                || phaseOrdinal < 0 || phaseOrdinal >= PlanningProgressSnapshot.Phase.values().length) {
            throw new DecoderException("Invalid planning progress enum ordinal");
        }
        long elapsedMillis = buf.readVarLong();
        if (elapsedMillis < 0L) throw new DecoderException("Negative planning elapsed time");
        return new PlanningProgressPacket(new PlanningProgressSnapshot(
                requestId, generation, recipeId,
                PlanningProgressSnapshot.State.values()[stateOrdinal],
                PlanningProgressSnapshot.Phase.values()[phaseOrdinal],
                elapsedMillis, buf.readComponent()));
    }

    public static void handle(PlanningProgressPacket packet,
                              Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> PlanningProgressClientPacketHandler.handle(packet.snapshot)));
        context.setPacketHandled(true);
    }

    public PlanningProgressSnapshot snapshot() {
        return snapshot;
    }
}
