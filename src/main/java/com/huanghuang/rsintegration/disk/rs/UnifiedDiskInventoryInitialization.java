package com.huanghuang.rsintegration.disk.rs;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** 异步背包 tick 只登记玩家，建盘和物品身份写入在服务器 tick 结束时执行。 */
final class UnifiedDiskInventoryInitialization {
    private static final Map<MinecraftServer, Map<UUID, ServerPlayer>> PENDING = new IdentityHashMap<>();

    private UnifiedDiskInventoryInitialization() {}

    static void request(ServerLevel level, ServerPlayer player) {
        MinecraftServer server = level.getServer();
        synchronized (PENDING) {
            if (server.isStopped()) return;
            PENDING.computeIfAbsent(server, ignored -> new LinkedHashMap<>()).put(player.getUUID(), player);
        }
    }

    static void process(MinecraftServer server) {
        if (!server.isSameThread()) throw new IllegalStateException("统一盘背包初始化必须在服务器主线程执行");
        Map<UUID, ServerPlayer> players;
        synchronized (PENDING) {
            players = PENDING.remove(server);
        }
        if (players == null) return;
        for (ServerPlayer player : players.values()) {
            if (player.isRemoved() || player.hasDisconnected()
                    || server.getPlayerList().getPlayer(player.getUUID()) != player) continue;
            ServerLevel level = player.serverLevel();
            if (level.getServer() != server) continue;
            // 重新读取全部当前槽位，不持有旧 ItemStack，移动、丢弃和重连不会写入过期物品。
            Inventory inventory = player.getInventory();
            for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
                ItemStack stack = inventory.getItem(slot);
                if (!stack.isEmpty() && stack.getItem() instanceof UnifiedDiskItem item && !item.isValid(stack)) {
                    item.initialize(stack, level, player.getUUID());
                }
            }
        }
    }

    static void forget(ServerLevel level, ServerPlayer player) {
        MinecraftServer server = level.getServer();
        synchronized (PENDING) {
            Map<UUID, ServerPlayer> players = PENDING.get(server);
            if (players == null) return;
            players.remove(player.getUUID(), player);
            if (players.isEmpty()) PENDING.remove(server);
        }
    }

    static void clear(MinecraftServer server) {
        synchronized (PENDING) {
            PENDING.remove(server);
        }
    }
}
