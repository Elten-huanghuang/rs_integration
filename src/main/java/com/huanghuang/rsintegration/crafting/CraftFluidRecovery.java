package com.huanghuang.rsintegration.crafting;
import com.huanghuang.rsintegration.crafting.endpoint.CraftStorageEndpoint;

import com.huanghuang.rsintegration.crafting.recovery.FluidTokenItem;
import com.huanghuang.rsintegration.crafting.recovery.FluidRecoveryDelivery;
import com.huanghuang.rsintegration.crafting.recovery.FluidRecoveryStore;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nullable;
import java.util.UUID;

/** 将流体退款封装为正常物品；离线或背包满时保底暂存。 */
public final class CraftFluidRecovery {
    private static final Logger LOGGER = LogManager.getLogger("RS Integration");
    private CraftFluidRecovery() { }

    public static void retainToEndpoint(ServerPlayer player, ItemStack token,
                                        @Nullable CraftStorageEndpoint endpoint) {
        retainToEndpoint(player.server, player.getUUID(), player, token, endpoint);
    }

    public static void retainToEndpoint(MinecraftServer server, UUID owner,
                                        @Nullable ServerPlayer player, ItemStack token,
                                        @Nullable CraftStorageEndpoint endpoint) {
        if (!FluidTokenItem.isToken(token) || token.getTag() == null || token.getCount() <= 0) {
            throw new IllegalArgumentException("流体退款缺少可恢复的身份或数量");
        }
        CompoundTag fluid = token.getTag().copy();
        fluid.putInt("Amount", token.getCount());
        String networkId = networkId(endpoint);
        if (FluidRecoveryDelivery.giveOnline(player, fluid, networkId)) return;
        boolean first = FluidRecoveryStore.get(server).retainRaw(owner, fluid, networkId);
        if (first) {
            LOGGER.warn("[RSI] 流体退款开始暂存 owner={} network={}", owner, networkId);
        }
        if (first && player != null && !player.hasDisconnected()) {
            player.sendSystemMessage(Component.translatable("rsi.fluid_recovery.pending"));
        }
    }

    @Nullable
    private static String networkId(@Nullable CraftStorageEndpoint endpoint) {
        try {
            if (endpoint != null && "refinedstorage".equals(
                    endpoint.session().reference().backendId().value())) {
                return endpoint.session().reference().networkId();
            }
        } catch (RuntimeException | LinkageError unavailable) {
            LOGGER.warn("[RSI] 无法识别流体退款的原 RS 网络，按玩家身份暂存", unavailable);
        }
        return null;
    }
}
