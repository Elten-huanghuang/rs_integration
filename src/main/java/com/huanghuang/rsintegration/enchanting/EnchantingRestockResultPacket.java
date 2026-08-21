package com.huanghuang.rsintegration.enchanting;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public record EnchantingRestockResultPacket(Status status, int inserted, int missing) {
    public enum Status { COMPLETE, PARTIAL, NO_NETWORK, NO_PERMISSION, INVALID }

    public EnchantingRestockResultPacket {
        if (status == null || inserted < 0 || inserted > 64 || missing < 0 || missing > 64) {
            throw new IllegalArgumentException("invalid enchanting restock result");
        }
    }

    public static void encode(EnchantingRestockResultPacket packet, FriendlyByteBuf buffer) {
        buffer.writeEnum(packet.status);
        buffer.writeVarInt(packet.inserted);
        buffer.writeVarInt(packet.missing);
    }

    public static EnchantingRestockResultPacket decode(FriendlyByteBuf buffer) {
        return new EnchantingRestockResultPacket(
                buffer.readEnum(Status.class), buffer.readVarInt(), buffer.readVarInt());
    }

    public static void handle(EnchantingRestockResultPacket packet,
                              Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> EnchantingRestockClientPacketHandler.handle(packet)));
        context.setPacketHandled(true);
    }
}
