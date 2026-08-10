package com.huanghuang.rsintegration.network.binding;

import com.huanghuang.rsintegration.network.packet.NetworkHandler;
import com.huanghuang.rsintegration.network.packet.NetworkPacketIds;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;

import java.util.Optional;
import java.util.function.Supplier;

public record NearbyBindingRequestPacket() {

    private static boolean registered;

    public static void encode(NearbyBindingRequestPacket packet, FriendlyByteBuf buffer) {}

    public static NearbyBindingRequestPacket decode(FriendlyByteBuf buffer) {
        return new NearbyBindingRequestPacket();
    }

    public static void handle(NearbyBindingRequestPacket packet,
                              Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            if (context.getSender() != null) NearbyBindingService.request(context.getSender());
        });
        context.setPacketHandled(true);
    }

    public static void register() {
        if (registered) return;
        NetworkHandler.CHANNEL.registerMessage(
                NetworkPacketIds.NEARBY_BINDING_REQUEST,
                NearbyBindingRequestPacket.class,
                NearbyBindingRequestPacket::encode,
                NearbyBindingRequestPacket::decode,
                NearbyBindingRequestPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
        registered = true;
    }
}
