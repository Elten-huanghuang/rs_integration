package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.RSIntegrationMod;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

public final class BatchCraftManager {

    private static final BatchCraftManager INSTANCE = new BatchCraftManager();
    private final List<BatchCraftTask> activeTasks = new CopyOnWriteArrayList<>();

    private BatchCraftManager() {}

    public static BatchCraftManager getInstance() { return INSTANCE; }

    public void addTask(BatchCraftTask task) {
        activeTasks.add(task);
    }

    public void removeTask(BatchCraftTask task) {
        activeTasks.remove(task);
    }

    public void cancelAllForPlayer(UUID playerId) {
        Iterator<BatchCraftTask> it = activeTasks.iterator();
        while (it.hasNext()) {
            BatchCraftTask task = it.next();
            if (task.getPlayerId().equals(playerId)) {
                task.markFailed("Player disconnected");
                activeTasks.remove(task);
                RSIntegrationMod.LOGGER.debug("[RSI-Batch] Cancelled task for disconnected player {}", playerId);
            }
        }
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        MinecraftServer server = event.getServer();
        if (server == null || server.getPlayerList() == null) return;

        for (BatchCraftTask task : activeTasks) {
            try {
                task.tick(server);
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.error("[RSI-Batch] Task tick error for recipe {}:", task.getRecipeId(), e);
                task.markFailed("Internal error: " + e.getMessage());
                removeTask(task);
            }
        }
    }
}
