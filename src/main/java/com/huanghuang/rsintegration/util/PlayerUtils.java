package com.huanghuang.rsintegration.util;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.refinedmods.refinedstorage.api.network.INetwork;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.ItemHandlerHelper;

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

    /**
     * Safely gives an item to a player, with fallbacks when the player's chunk
     * is unloaded (which would silently void items). Falls back to RS network
     * insertion, then to world-spawn drop.
     */
    public static void safeGiveToPlayer(ServerPlayer player, ItemStack stack, @Nullable INetwork network) {
        if (stack.isEmpty()) return;
        if (player.level().hasChunkAt(player.blockPosition())) {
            // Split large stacks into max-size chunks to avoid spawning an
            // excessive number of item entities when the player's inventory
            // is full (e.g. batch crafting 1000 planks → 16 entities, not 1000).
            while (!stack.isEmpty()) {
                int split = Math.min(stack.getMaxStackSize(), stack.getCount());
                ItemStack chunk = stack.split(split);
                ItemHandlerHelper.giveItemToPlayer(player, chunk);
            }
            return;
        }
        if (network != null) {
            // insertItem returns whatever the network could not store; if we
            // discard it (RS full / no matching storage) those items are voided.
            ItemStack remainder = network.insertItem(stack, stack.getCount(),
                com.refinedmods.refinedstorage.api.util.Action.PERFORM);
            int stored = stack.getCount() - remainder.getCount();
            if (stored > 0) {
                RSIntegrationMod.LOGGER.warn("[RSI] Refund redirected to RS network (player chunk unloaded): {} x{}",
                    stack.getHoverName().getString(), stored);
            }
            // Fall through to world-spawn drop for anything the network rejected.
            stack = remainder;
        }
        if (!stack.isEmpty()) {
            var spawnLevel = player.getServer().overworld();
            if (spawnLevel == null) return;
            var spawnPos = spawnLevel.getSharedSpawnPos();
            spawnLevel.addFreshEntity(
                new ItemEntity(spawnLevel,
                    spawnPos.getX() + 0.5, spawnPos.getY() + 0.5, spawnPos.getZ() + 0.5, stack));
            RSIntegrationMod.LOGGER.warn("[RSI] Refund dropped at world spawn (player {} in unloaded chunk): {} x{}",
                player.getGameProfile().getName(), stack.getHoverName().getString(), stack.getCount());
        }
    }
}
