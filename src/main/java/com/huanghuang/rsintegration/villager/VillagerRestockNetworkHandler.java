package com.huanghuang.rsintegration.villager;

import com.huanghuang.rsintegration.network.packet.NetworkHandler;
import com.huanghuang.rsintegration.network.packet.NetworkPacketIds;
import net.minecraftforge.network.NetworkDirection;
import java.util.Optional;

public final class VillagerRestockNetworkHandler {
    private static boolean registered;
    private VillagerRestockNetworkHandler() {}
    public static void register() {
        if (registered) return;
        NetworkHandler.CHANNEL.registerMessage(NetworkPacketIds.VILLAGER_RESTOCK_REQUEST,
                VillagerRestockRequestPacket.class, VillagerRestockRequestPacket::encode,
                VillagerRestockRequestPacket::decode, VillagerRestockRequestPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
        NetworkHandler.CHANNEL.registerMessage(NetworkPacketIds.VILLAGER_RESTOCK_RESULT,
                VillagerRestockResultPacket.class, VillagerRestockResultPacket::encode,
                VillagerRestockResultPacket::decode, VillagerRestockResultPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        registered=true;
    }
}
