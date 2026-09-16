package com.huanghuang.rsintegration.anvilmemory;

import com.huanghuang.rsintegration.network.packet.NetworkHandler;
import com.huanghuang.rsintegration.network.packet.NetworkPacketIds;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.PacketDistributor;

import java.util.Optional;

public final class AnvilMemoryNetworkHandler {
    private static boolean registered;

    private AnvilMemoryNetworkHandler() {}

    public static void register() {
        if (registered) return;
        NetworkHandler.CHANNEL.registerMessage(NetworkPacketIds.ANVIL_MEMORY_REQUEST,
                AnvilMemoryRequestPacket.class, AnvilMemoryRequestPacket::encode,
                AnvilMemoryRequestPacket::decode, AnvilMemoryRequestPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
        NetworkHandler.CHANNEL.registerMessage(NetworkPacketIds.ANVIL_MEMORY_SYNC,
                AnvilMemorySyncPacket.class, AnvilMemorySyncPacket::encode,
                AnvilMemorySyncPacket::decode, AnvilMemorySyncPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        registered = true;
    }

    public static void sendSync(ServerPlayer player, AnvilMemoryAdapter adapter) {
        NetworkHandler.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                AnvilMemorySyncPacket.syncEntries(adapter.id(),
                        AnvilMemoryData.getEntries(player, adapter.id())));
    }
}
