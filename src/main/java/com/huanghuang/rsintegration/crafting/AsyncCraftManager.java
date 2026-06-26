package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.RSIntegrationMod;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
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
                // Collect first, then abort outside the iterator —
                // abort() → fireOnDone() callback removes from activeChains.
                List<AsyncCraftChain> toAbort = new ArrayList<>();
                for (AsyncCraftChain existing : activeChains) {
                    if (existing != chain && existing.belongsTo(playerId)) {
                        toAbort.add(existing);
                    }
                }
                for (AsyncCraftChain existing : toAbort) {
                    existing.abort("Superseded by new chain");
                    activeChains.remove(existing);
                    RSIntegrationMod.LOGGER.debug("[RSI-AsyncMgr] Aborted existing chain for player {}", playerId);
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
        // Collect first, then abort outside the lock — abort() → fireOnDone()
        // callback removes from activeChains, which confuses the iterator.
        List<AsyncCraftChain> toAbort;
        synchronized (activeChains) {
            toAbort = activeChains.stream()
                    .filter(c -> c.belongsTo(playerId))
                    .collect(java.util.stream.Collectors.toList());
        }
        for (AsyncCraftChain chain : toAbort) {
            chain.abort("Player disconnected");
            synchronized (activeChains) {
                activeChains.remove(chain);
            }
            RSIntegrationMod.LOGGER.debug("[RSI-AsyncMgr] Cancelled chain for disconnected player {}", playerId);
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
                String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                chain.abort("Internal error: " + msg);
                synchronized (activeChains) {
                    activeChains.remove(chain);
                }
            }
        }
    }
}
