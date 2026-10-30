package com.huanghuang.rsintegration.feature.foodorder;

import com.huanghuang.rsintegration.feature.foodorder.client.FoodOrderScreen;
import com.huanghuang.rsintegration.feature.foodorder.network.FoodOrderListPacket;
import com.huanghuang.rsintegration.feature.foodorder.network.FoodOrderRequestListPacket;
import com.huanghuang.rsintegration.feature.foodorder.network.FoodOrderResultPacket;
import com.huanghuang.rsintegration.feature.foodorder.network.FoodOrderSubmitPacket;
import com.huanghuang.rsintegration.network.packet.NetworkHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/** 客户端入口：打开界面、请求列表、提交订单、处理结果提示。 */
@OnlyIn(Dist.CLIENT)
public final class FoodOrderClient {

    private FoodOrderClient() {}

    public static void openScreen() {
        Minecraft.getInstance().setScreen(new FoodOrderScreen());
    }

    public static void requestList() {
        NetworkHandler.CHANNEL.sendToServer(new FoodOrderRequestListPacket());
    }

    public static void submit(FoodOrderEntry entry, boolean all, long epoch) {
        ItemStack target = all || entry == null ? ItemStack.EMPTY : entry.stack().copy();
        var recipeId = all || entry == null ? null : entry.recipeId();
        NetworkHandler.CHANNEL.sendToServer(
                new FoodOrderSubmitPacket(target, all, recipeId, epoch));
    }

    public static void acceptList(FoodOrderListPacket packet) {
        if (Minecraft.getInstance().screen instanceof FoodOrderScreen screen) {
            screen.setData(packet);
        }
    }

    public static void acceptResult(FoodOrderResultPacket packet) {
        Component message;
        if (packet.code() == FoodOrderResultPacket.FAILED) {
            message = Component.translatable(packet.messageKey());
            if (!packet.extra().isEmpty()) {
                message = Component.translatable("rsi.food_order.result.detail",
                        message, Component.literal(packet.extra()));
            }
        } else if (packet.code() == FoodOrderResultPacket.PARTIAL) {
            message = Component.translatable(packet.messageKey(),
                    packet.accepted(), packet.skipped());
        } else {
            message = Component.translatable(packet.messageKey(), packet.accepted());
        }
        Minecraft.getInstance().gui.setOverlayMessage(message, false);
        if (packet.code() == FoodOrderResultPacket.SUCCESS
                || packet.code() == FoodOrderResultPacket.PARTIAL) {
            requestList();
        }
    }
}
