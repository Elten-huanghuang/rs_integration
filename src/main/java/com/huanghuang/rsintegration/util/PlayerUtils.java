package com.huanghuang.rsintegration.util;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import javax.annotation.Nullable;
import java.util.UUID;

public final class PlayerUtils {

    private PlayerUtils() {}

    @Nullable
    public static ServerPlayer getOnlinePlayer(MinecraftServer server, UUID playerId) {
        if (server == null || server.getPlayerList() == null) return null;
        return server.getPlayerList().getPlayer(playerId);
    }

    public static boolean isPlayerOnline(ServerPlayer player) {
        return player != null && !player.hasDisconnected();
    }

    /** Send a system message to the player, guarding against disconnected state. */
    public static void safeSendMessage(ServerPlayer player, Component message) {
        if (player != null && !player.hasDisconnected()) {
            player.sendSystemMessage(message);
        }
    }

    /** Convenience: resolve player by UUID then send. No-op if offline. */
    public static void safeSendMessage(MinecraftServer server, UUID playerId, Component message) {
        ServerPlayer player = getOnlinePlayer(server, playerId);
        if (player != null && !player.hasDisconnected()) {
            player.sendSystemMessage(message);
        }
    }
}
