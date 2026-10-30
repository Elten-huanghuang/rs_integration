package com.huanghuang.rsintegration.feature.foodorder.network;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.feature.foodorder.FoodOrderListService;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** 客户端请求刷新点餐列表。 */
public final class FoodOrderRequestListPacket {

    public static void encode(FoodOrderRequestListPacket packet, FriendlyByteBuf buffer) {
        // 空包
    }

    public static FoodOrderRequestListPacket decode(FriendlyByteBuf buffer) {
        return new FoodOrderRequestListPacket();
    }

    public static void handle(FoodOrderRequestListPacket packet,
                              Supplier<NetworkEvent.Context> context) {
        context.get().enqueueWork(() -> {
            ServerPlayer sender = context.get().getSender();
            if (sender != null && !(sender instanceof FakePlayer)) {
                try {
                    FoodOrderListService.request(sender);
                } catch (Throwable throwable) {
                    RSIntegrationMod.LOGGER.warn(
                            "[RSI-FoodOrder] 构建点餐列表失败", throwable);
                }
            }
        });
        context.get().setPacketHandled(true);
    }
}
