package com.huanghuang.rsintegration.villager.tradelock;

import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/** Common server hook shared by optional trade-reroll mod mixins. */
public final class VillagerTradeLockHooks {
    private VillagerTradeLockHooks() {}

    public static boolean blockRefresh(ServerPlayer player) {
        if (!RSIntegrationConfig.ENABLE_VILLAGER_TRADE_LOCK.get()) return false;
        if (!VillagerTradeLockService.shouldBlock(player)) return false;
        long now = System.currentTimeMillis();
        if (VillagerTradeLockService.shouldNotify(player, now)) {
            player.displayClientMessage(Component.translatable("rsi.villager.trade_lock.blocked"), true);
        }
        return true;
    }
}
