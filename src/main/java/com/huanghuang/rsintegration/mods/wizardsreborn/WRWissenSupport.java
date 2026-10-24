package com.huanghuang.rsintegration.mods.wizardsreborn;

import com.huanghuang.rsintegration.RSIntegrationMod;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

import java.lang.reflect.Method;
import java.util.List;

/** Wizard's Reborn 秘蕴前置条件共享逻辑。 */
final class WRWissenSupport {
    private WRWissenSupport() {}

    /**
     * 从玩家携带的秘蕴存储物品补足机台缺口，只转移数值，不消耗物品本身。
     */
    static int transferFromPlayerItems(ServerPlayer player, Object machine,
                                       int current, int deficit) {
        if (player == null || machine == null || deficit <= 0) return 0;
        try {
            Class<?> util = Class.forName(
                    "mod.maxbogomol.wizards_reborn.api.wissen.WissenUtil");
            Method getItems = util.getMethod("getWissenItems", Player.class);
            Object rawItems = getItems.invoke(null, player);
            if (!(rawItems instanceof List<?> items) || items.isEmpty()) return 0;
            Method storedMethod = util.getMethod("getWissenInItems", List.class);
            int stored = ((Number) storedMethod.invoke(null, items)).intValue();
            int safeCurrent = Math.max(0, current);
            int maximum = maximumWissen(machine, safeCurrent);
            int transfer = transferAmount(
                    safeCurrent, safeCurrent + deficit, stored, maximum);
            if (transfer <= 0) return 0;

            Method remove = util.getMethod(
                    "removeWissenFromWissenItems", List.class, int.class);
            remove.invoke(null, items, transfer);
            Method add = machine.getClass().getMethod("addWissen", int.class);
            add.invoke(machine, transfer);
            RSIntegrationMod.LOGGER.debug(
                    "[RSI-WR] transferred Wissen from player items: {} ({} -> {})",
                    transfer, safeCurrent, safeCurrent + transfer);
            return transfer;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            RSIntegrationMod.LOGGER.debug(
                    "[RSI-WR] Player Wissen transfer unavailable", exception);
            return 0;
        }
    }

    static int transferAmount(int current, int required, int stored, int maximum) {
        int deficit = Math.max(0, required - Math.max(0, current));
        int room = Math.max(0, maximum - Math.max(0, current));
        return Math.min(Math.max(0, stored), Math.min(deficit, room));
    }

    private static int maximumWissen(Object machine, int fallback) {
        try {
            Method method = machine.getClass().getMethod("getMaxWissen");
            return Math.max(0, (int) method.invoke(machine));
        } catch (ReflectiveOperationException | RuntimeException exception) {
            RSIntegrationMod.LOGGER.debug(
                    "[RSI-WR] Failed to read maximum Wissen", exception);
        }
        return Math.max(fallback, Integer.MAX_VALUE);
    }
}
