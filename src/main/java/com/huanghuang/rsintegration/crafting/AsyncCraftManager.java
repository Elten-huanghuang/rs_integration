package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.RSIntegrationMod;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

/**
 * Drives all active {@link AsyncCraftChain} instances every server tick
 * and cleans up chains owned by a player who disconnects.
 */
public final class AsyncCraftManager {

    private static final AsyncCraftManager INSTANCE = new AsyncCraftManager();
    private final List<AsyncCraftChain> activeChains = new ArrayList<>();

    private AsyncCraftManager() {}

    public static AsyncCraftManager getInstance() { return INSTANCE; }

    public void submit(AsyncCraftChain chain) {
        // Abort any existing chain for the same player to prevent resource
        // conflicts between independent ExtractionLedger instances.
        ServerPlayer player = chain.getPlayer();
        synchronized (activeChains) {
            if (player != null) {
                UUID playerId = player.getUUID();
                Iterator<AsyncCraftChain> it = activeChains.iterator();
                while (it.hasNext()) {
                    AsyncCraftChain existing = it.next();
                    if (existing != chain && existing.belongsTo(playerId)) {
                        existing.abort("Superseded by new chain");
                        it.remove();
                        RSIntegrationMod.LOGGER.debug("[RSI-AsyncMgr] Aborted existing chain for player {}", playerId);
                    }
                }
            }
            activeChains.add(chain);
            // Register callback so the chain notifies us when it terminates
            chain.onDone(() -> {
                synchronized (activeChains) {
                    activeChains.remove(chain);
                    RSIntegrationMod.LOGGER.debug("[RSI-AsyncMgr] Chain removed via callback: player={} state={}",
                            player != null ? player.getName().getString() : "?", chain.state());
                }
            });
        }
        RSIntegrationMod.LOGGER.debug("[RSI-AsyncMgr] Chain submitted: player={} steps={}",
                player != null ? player.getName().getString() : "?", chain.stepsCount());
    }

    public boolean hasActiveChainFor(UUID playerId) {
        return getChain(playerId) != null;
    }

    @Nullable
    public AsyncCraftChain getChain(ServerPlayer player) {
        return getChain(player.getUUID());
    }

    @Nullable
    public AsyncCraftChain getChain(UUID playerId) {
        synchronized (activeChains) {
            for (AsyncCraftChain chain : activeChains) {
                if (chain.belongsTo(playerId)) return chain;
            }
        }
        return null;
    }

    public void cancelAllForPlayer(UUID playerId) {
        synchronized (activeChains) {
            Iterator<AsyncCraftChain> it = activeChains.iterator();
            while (it.hasNext()) {
                AsyncCraftChain chain = it.next();
                if (chain.belongsTo(playerId)) {
                    chain.abort("Player disconnected");
                    it.remove();
                    RSIntegrationMod.LOGGER.debug("[RSI-AsyncMgr] Cancelled chain for disconnected player {}", playerId);
                }
            }
        }
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        MinecraftServer server = event.getServer();
        if (server == null || server.getPlayerList() == null) return;

        // Snapshot to avoid CME: chain.tick() → fireOnDone() callback
        // removes the chain from activeChains inside this synchronized block.
        List<AsyncCraftChain> snapshot;
        synchronized (activeChains) {
            snapshot = new ArrayList<>(activeChains);
        }
        for (AsyncCraftChain chain : snapshot) {
            try {
                chain.tick();
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.error("[RSI-AsyncMgr] Chain tick error", e);
                chain.abort("Internal error: " + e.getMessage());
                synchronized (activeChains) {
                    activeChains.remove(chain);
                }
            }
        }
    }
}
