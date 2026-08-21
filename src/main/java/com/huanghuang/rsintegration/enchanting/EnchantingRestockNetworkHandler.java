package com.huanghuang.rsintegration.enchanting;

import com.huanghuang.rsintegration.network.packet.NetworkHandler;
import com.huanghuang.rsintegration.network.packet.NetworkPacketIds;
import net.minecraftforge.network.NetworkDirection;

import java.util.Optional;

public final class EnchantingRestockNetworkHandler {
    private static boolean registered;

    private EnchantingRestockNetworkHandler() {}

    public static void register() {
        if (registered) return;
        NetworkHandler.CHANNEL.registerMessage(NetworkPacketIds.ENCHANTING_RESTOCK_REQUEST,
                EnchantingRestockRequestPacket.class,
                EnchantingRestockRequestPacket::encode,
                EnchantingRestockRequestPacket::decode,
                EnchantingRestockRequestPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
        NetworkHandler.CHANNEL.registerMessage(NetworkPacketIds.ENCHANTING_RESTOCK_RESULT,
                EnchantingRestockResultPacket.class,
                EnchantingRestockResultPacket::encode,
                EnchantingRestockResultPacket::decode,
                EnchantingRestockResultPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        registered = true;
    }
}
