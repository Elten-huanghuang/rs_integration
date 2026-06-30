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
        // Multiple chains per player are allowed when the player has multiple
        // bound machines of the same type.  startModStep() iterates all bound
        // machines and skips busy ones, so independent chains route to different
        // machines naturally.  If no free machine is found the second chain
        // aborts itself without touching the first.
        UUID playerId = chain.getPlayerId();
        synchronized (activeChains) {
            activeChains.add(chain);
            // Register callback so the chain notifies us when it terminates.
            // Capture UUID (not ServerPlayer) to avoid zombie references.
            final UUID capturedId = playerId;
            chain.onDone(() -> {
                synchronized (activeChains) {
                    activeChains.remove(chain);
                    RSIntegrationMod.LOGGER.debug("[RSI-AsyncMgr] Chain removed via callback: player={} state={}",
                            capturedId, chain.state());
                }
            });
        }
        RSIntegrationMod.LOGGER.debug("[RSI-AsyncMgr] Chain submitted: player={} steps={}",
                playerId, chain.stepsCount());
    }

    public boolean hasActiveChainFor(UUID playerId) {
        return getChain(playerId) != null;
    }

    public int getActiveChainCount() {
        synchronized (activeChains) {
            return activeChains.size();
        }
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

    /** Remove a specific chain instance from the active list without aborting. */
    public void remove(AsyncCraftChain chain) {
        synchronized (activeChains) {
            activeChains.remove(chain);
        }
    }

    /**
     * Abort all active chains during server shutdown.  Best-effort: logs
     * errors but never throws, so shutdown is not blocked.  The RS network
     * may already be partially torn down by the time this runs.
     */
    public static void abortAll() {
        List<AsyncCraftChain> snapshot;
        synchronized (INSTANCE.activeChains) {
            snapshot = new ArrayList<>(INSTANCE.activeChains);
            INSTANCE.activeChains.clear();
        }
        for (AsyncCraftChain chain : snapshot) {
            try {
                chain.abort("Server stopping");
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.error("[RSI-AsyncMgr] Failed to abort chain during shutdown for player {}",
                        chain.getPlayerId(), e);
            }
        }
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
            RSIntegrationMod.LOGGER.debug("[RSI-AsyncMgr] Cancelled chain for disconnected player {}", playerId);
        }
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        MinecraftServer server = event.getServer();
        if (server == null || server.getPlayerList() == null) return;

        long tickStart = System.nanoTime();

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
        com.huanghuang.rsintegration.command.PerformanceMonitor.recordTick(
                System.nanoTime() - tickStart);
    }
}
