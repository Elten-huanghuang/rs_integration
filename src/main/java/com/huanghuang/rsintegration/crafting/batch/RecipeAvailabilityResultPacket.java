package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.crafting.availability.MaterialAvailability;
import com.huanghuang.rsintegration.crafting.availability.RecipeAvailabilityKey;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public record RecipeAvailabilityResultPacket(RecipeAvailabilityKey key, long ticket,
                                             MaterialAvailability state) {
    public void encode(FriendlyByteBuf buf) {
        key.encode(buf);
        buf.writeLong(ticket);
        buf.writeEnum(state);
    }

    public static RecipeAvailabilityResultPacket decode(FriendlyByteBuf buf) {
        return new RecipeAvailabilityResultPacket(RecipeAvailabilityKey.decode(buf), buf.readLong(),
                buf.readEnum(MaterialAvailability.class));
    }

    public static void handle(RecipeAvailabilityResultPacket packet, Supplier<NetworkEvent.Context> supplier) {
        var context = supplier.get();
        context.enqueueWork(() -> net.minecraftforge.fml.DistExecutor.unsafeRunWhenOn(
                net.minecraftforge.api.distmarker.Dist.CLIENT,
                () -> () -> RecipeAvailabilityClientPacketHandler.accept(packet)));
        context.setPacketHandled(true);
    }
}
