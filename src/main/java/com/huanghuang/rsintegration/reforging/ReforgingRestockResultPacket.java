package com.huanghuang.rsintegration.reforging;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public record ReforgingRestockResultPacket(Status status, int inserted, int missing) {
    public enum Status { COMPLETE, PARTIAL, NO_NETWORK, NO_PERMISSION, INVALID }

    public static void encode(ReforgingRestockResultPacket packet, FriendlyByteBuf buffer) {
        buffer.writeEnum(packet.status);
        buffer.writeVarInt(packet.inserted);
        buffer.writeVarInt(packet.missing);
    }

    public static ReforgingRestockResultPacket decode(FriendlyByteBuf buffer) {
        Status status = buffer.readEnum(Status.class);
        int inserted = buffer.readVarInt();
        int missing = buffer.readVarInt();
        if (inserted < 0 || inserted > 64 || missing < 0 || missing > 64) {
            throw new IllegalArgumentException("invalid reforging restock counts");
        }
        return new ReforgingRestockResultPacket(status, inserted, missing);
    }

    public static void handle(ReforgingRestockResultPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> ReforgingRestockClientPacketHandler.handle(packet)));
        context.setPacketHandled(true);
    }
}
