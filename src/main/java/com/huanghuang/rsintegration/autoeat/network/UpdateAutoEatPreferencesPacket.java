package com.huanghuang.rsintegration.autoeat.network;

import com.huanghuang.rsintegration.autoeat.AutoEatMode;
import com.huanghuang.rsintegration.autoeat.AutoEatPreferences;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.network.NetworkEvent;

import javax.annotation.Nullable;
import java.util.Objects;
import java.util.function.Supplier;

/** Persists the client's current auto-eat mode and stack-food selection. */
public record UpdateAutoEatPreferencesPacket(
        AutoEatMode mode, @Nullable ResourceLocation selectedItem) {

    public UpdateAutoEatPreferencesPacket {
        Objects.requireNonNull(mode, "mode");
    }

    public static void encode(UpdateAutoEatPreferencesPacket packet, FriendlyByteBuf buffer) {
        buffer.writeEnum(packet.mode);
        buffer.writeBoolean(packet.selectedItem != null);
        if (packet.selectedItem != null) buffer.writeResourceLocation(packet.selectedItem);
    }

    public static UpdateAutoEatPreferencesPacket decode(FriendlyByteBuf buffer) {
        AutoEatMode mode = buffer.readEnum(AutoEatMode.class);
        ResourceLocation selected = buffer.readBoolean() ? buffer.readResourceLocation() : null;
        return new UpdateAutoEatPreferencesPacket(mode, selected);
    }

    public static void handle(UpdateAutoEatPreferencesPacket packet,
                              Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            var sender = context.getSender();
            if (sender != null && !(sender instanceof FakePlayer)) {
                AutoEatPreferences.save(sender, packet.mode, packet.selectedItem);
            }
        });
        context.setPacketHandled(true);
    }
}
