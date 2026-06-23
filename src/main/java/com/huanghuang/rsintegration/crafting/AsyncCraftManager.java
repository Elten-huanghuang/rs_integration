package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.RSIntegrationMod;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Drives all active {@link AsyncCraftChain} instances every server tick
 * and cleans up chains owned by a player who disconnects.
 */
public final class AsyncCraftManager {

    private static final AsyncCraftManager INSTANCE = new AsyncCraftManager();
    private final List<AsyncCraftChain> activeChains = new CopyOnWriteArrayList<>();

    private AsyncCraftManager() {}

    public static AsyncCraftManager getInstance() { return INSTANCE; }

    public void submit(AsyncCraftChain chain) {
        // Abort any existing chain for the same player to prevent resource
        // conflicts between independent ExtractionLedger instances.
        ServerPlayer player = chain.getPlayer();
        if (player != null) {
            UUID playerId = player.getUUID();
            Iterator<AsyncCraftChain> it = activeChains.iterator();
            while (it.hasNext()) {
                AsyncCraftChain existing = it.next();
                if (existing != chain && existing.belongsTo(playerId)) {
                    existing.abort("Superseded by new chain");
                    activeChains.remove(existing);
                    RSIntegrationMod.LOGGER.debug("[RSI-AsyncMgr] Aborted existing chain for player {}", playerId);
                }
            }
        }
        activeChains.add(chain);
        RSIntegrationMod.LOGGER.debug("[RSI-AsyncMgr] Chain submitted: player={} steps={}",
                player != null ? player.getName().getString() : "?", chain.stepsCount());
    }

    public boolean hasActiveChainFor(UUID playerId) {
        for (AsyncCraftChain chain : activeChains) {
            if (chain.belongsTo(playerId)) return true;
        }
        return false;
    }

    public void cancelAllForPlayer(UUID playerId) {
        Iterator<AsyncCraftChain> it = activeChains.iterator();
        while (it.hasNext()) {
            AsyncCraftChain chain = it.next();
            if (chain.belongsTo(playerId)) {
                chain.abort("Player disconnected");
                activeChains.remove(chain);
                RSIntegrationMod.LOGGER.debug("[RSI-AsyncMgr] Cancelled chain for disconnected player {}", playerId);
            }
        }
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        MinecraftServer server = event.getServer();
        if (server == null || server.getPlayerList() == null) return;

        Iterator<AsyncCraftChain> it = activeChains.iterator();
        while (it.hasNext()) {
            AsyncCraftChain chain = it.next();
            try {
                if (chain.tick()) {
                    activeChains.remove(chain);
                }
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.error("[RSI-AsyncMgr] Chain tick error", e);
                chain.abort("Internal error: " + e.getMessage());
                activeChains.remove(chain);
            }
        }
    }
}
