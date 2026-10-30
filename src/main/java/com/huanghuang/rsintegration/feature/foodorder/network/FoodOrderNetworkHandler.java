package com.huanghuang.rsintegration.feature.foodorder.network;

import com.huanghuang.rsintegration.network.packet.NetworkHandler;
import com.huanghuang.rsintegration.network.packet.NetworkPacketIds;
import net.minecraftforge.network.NetworkDirection;

import java.util.Optional;

public final class FoodOrderNetworkHandler {

    private static boolean registered;

    private FoodOrderNetworkHandler() {}

    public static void register() {
        if (registered) {
            return;
        }
        var channel = NetworkHandler.CHANNEL;
        channel.registerMessage(NetworkPacketIds.FOOD_ORDER_REQUEST_LIST,
                FoodOrderRequestListPacket.class,
                FoodOrderRequestListPacket::encode,
                FoodOrderRequestListPacket::decode,
                FoodOrderRequestListPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
        channel.registerMessage(NetworkPacketIds.FOOD_ORDER_SUBMIT,
                FoodOrderSubmitPacket.class,
                FoodOrderSubmitPacket::encode,
                FoodOrderSubmitPacket::decode,
                FoodOrderSubmitPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
        channel.registerMessage(NetworkPacketIds.FOOD_ORDER_LIST,
                FoodOrderListPacket.class,
                FoodOrderListPacket::encode,
                FoodOrderListPacket::decode,
                FoodOrderListPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        channel.registerMessage(NetworkPacketIds.FOOD_ORDER_RESULT,
                FoodOrderResultPacket.class,
                FoodOrderResultPacket::encode,
                FoodOrderResultPacket::decode,
                FoodOrderResultPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        registered = true;
    }
}
