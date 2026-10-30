package com.huanghuang.rsintegration.feature.foodorder.network;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.feature.foodorder.FoodOrderService;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.network.NetworkEvent;

import javax.annotation.Nullable;
import java.util.function.Supplier;

/** 客户端提交一份或全部点餐请求。 */
public final class FoodOrderSubmitPacket {

    private final ItemStack target;
    private final boolean all;
    @Nullable
    private final ResourceLocation recipeId;
    private final long epoch;

    public FoodOrderSubmitPacket(ItemStack target, boolean all,
                                 @Nullable ResourceLocation recipeId, long epoch) {
        this.target = target == null ? ItemStack.EMPTY : target.copy();
        this.all = all;
        this.recipeId = recipeId;
        this.epoch = epoch;
    }

    public static void encode(FoodOrderSubmitPacket packet, FriendlyByteBuf buffer) {
        buffer.writeItem(packet.target);
        buffer.writeBoolean(packet.all);
        buffer.writeBoolean(packet.recipeId != null);
        if (packet.recipeId != null) {
            buffer.writeResourceLocation(packet.recipeId);
        }
        buffer.writeVarLong(packet.epoch);
    }

    public static FoodOrderSubmitPacket decode(FriendlyByteBuf buffer) {
        ItemStack target = buffer.readItem();
        boolean all = buffer.readBoolean();
        ResourceLocation recipeId = buffer.readBoolean() ? buffer.readResourceLocation() : null;
        long epoch = buffer.readVarLong();
        return new FoodOrderSubmitPacket(target, all, recipeId, epoch);
    }

    public static void handle(FoodOrderSubmitPacket packet,
                              Supplier<NetworkEvent.Context> context) {
        context.get().enqueueWork(() -> {
            ServerPlayer sender = context.get().getSender();
            if (sender != null && !(sender instanceof FakePlayer)) {
                try {
                    FoodOrderService.submit(sender, packet.target, packet.all,
                            packet.recipeId, packet.epoch);
                } catch (Throwable throwable) {
                    RSIntegrationMod.LOGGER.warn(
                            "[RSI-FoodOrder] 提交点餐请求失败", throwable);
                }
            }
        });
        context.get().setPacketHandled(true);
    }
}
