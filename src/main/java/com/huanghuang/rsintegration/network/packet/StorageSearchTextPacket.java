package com.huanghuang.rsintegration.network.packet;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.function.Supplier;

/** Server-to-client search update for an active RS or BD storage terminal. */
public record StorageSearchTextPacket(int containerId, String text) {
    private static final int MAX_TEXT_LENGTH = 256;

    public StorageSearchTextPacket {
        text = text == null ? "" : text.substring(0, Math.min(text.length(), MAX_TEXT_LENGTH));
    }

    public static void encode(StorageSearchTextPacket packet, FriendlyByteBuf buf) {
        buf.writeVarInt(packet.containerId);
        buf.writeUtf(packet.text, MAX_TEXT_LENGTH);
    }

    public static StorageSearchTextPacket decode(FriendlyByteBuf buf) {
        return new StorageSearchTextPacket(buf.readVarInt(), buf.readUtf(MAX_TEXT_LENGTH));
    }

    public static void handle(StorageSearchTextPacket packet,
                              Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> StorageSearchClientPacketHandler
                        .apply(packet.containerId(), packet.text())));
        context.setPacketHandled(true);
    }

    public static void sendTo(ServerPlayer player, String text) {
        if (player == null || player.containerMenu == null) return;
        sendTo(player, player.containerMenu.containerId, text);
    }

    /** Use this overload when the caller captured the menu id before async work. */
    public static void sendTo(ServerPlayer player, int containerId, String text) {
        if (player == null || player.hasDisconnected() || player.isRemoved()
                || player.containerMenu == null
                || player.containerMenu.containerId != containerId) return;
        NetworkHandler.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new StorageSearchTextPacket(containerId, text));
    }

    public static void register() {
        NetworkHandler.CHANNEL.registerMessage(NetworkPacketIds.STORAGE_SEARCH_TEXT,
                StorageSearchTextPacket.class,
                StorageSearchTextPacket::encode,
                StorageSearchTextPacket::decode,
                StorageSearchTextPacket::handle,
                java.util.Optional.of(net.minecraftforge.network.NetworkDirection.PLAY_TO_CLIENT));
    }
}
