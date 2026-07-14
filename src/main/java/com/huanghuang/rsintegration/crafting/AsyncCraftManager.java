package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.graph.CaptureLeaseRegistry;
import com.huanghuang.rsintegration.crafting.graph.MachineLeaseRegistry;
import com.huanghuang.rsintegration.command.PerformanceMonitor;
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
    private final ActiveCraftRegistry<UUID, AsyncCraftChain> activeChains = new ActiveCraftRegistry<>();
    private final MachineLeaseRegistry machineLeases = new MachineLeaseRegistry();
    private final CaptureLeaseRegistry captureLeases = new CaptureLeaseRegistry();

    private AsyncCraftManager() {}

    public static AsyncCraftManager getInstance() { return INSTANCE; }

    public void submit(AsyncCraftChain chain) {
        UUID playerId = chain.getPlayerId();
        UUID craftId = chain.getCraftId();
        if (!activeChains.add(craftId, chain)) {
            throw new IllegalStateException("Duplicate async craft id: " + craftId);
        }
        chain.onDone(() -> {
            activeChains.remove(craftId);
            RSIntegrationMod.LOGGER.debug("[RSI-AsyncMgr] Chain removed via callback: craft={} player={} state={}",
                    craftId, playerId, chain.state());
        });
        RSIntegrationMod.LOGGER.debug("[RSI-AsyncMgr] Chain submitted: craft={} player={} steps={}",
                craftId, playerId, chain.stepsCount());
    }

    public boolean hasActiveChainFor(UUID playerId) {
        return getChain(playerId) != null;
    }

    public int getActiveChainCount() {
        return activeChains.size();
    }

    MachineLeaseRegistry machineLeases() {
        return machineLeases;
    }

    CaptureLeaseRegistry captureLeases() {
        return captureLeases;
    }

    @Nullable
    public AsyncCraftChain getCraft(UUID craftId) {
        return activeChains.get(craftId);
    }

    /** Immutable active-craft snapshot for status synchronization. */
    public List<AsyncCraftChain> activeCraftsFor(UUID playerId) {
        List<AsyncCraftChain> result = new ArrayList<>();
        for (AsyncCraftChain chain : activeChains.snapshot()) {
            if (chain.belongsTo(playerId) && !chain.isDone()) result.add(chain);
        }
        return List.copyOf(result);
    }

    @Nullable
    public AsyncCraftChain getChain(ServerPlayer player) {
        return getChain(player.getUUID());
    }

    @Nullable
    public AsyncCraftChain getChain(UUID playerId) {
        for (AsyncCraftChain chain : activeChains.snapshot()) {
            if (chain.belongsTo(playerId)) return chain;
        }
        return null;
    }

    public void remove(AsyncCraftChain chain) {
        activeChains.remove(chain.getCraftId());
    }

    /**
     * Abort all active chains during server shutdown.  Best-effort: logs
     * errors but never throws, so shutdown is not blocked.  The RS network
     * may already be partially torn down by the time this runs.
     */
    public static void abortAll() {
        // Keep entries registered until each chain reaches its terminal callback.
        // This preserves visibility when cleanup throws during shutdown.
        List<AsyncCraftChain> snapshot = INSTANCE.activeChains.snapshot();
        for (AsyncCraftChain chain : snapshot) {
            try {
                chain.abort("Server stopping");
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.error("[RSI-AsyncMgr] Failed to abort chain during shutdown for player {}",
                        chain.getPlayerId(), e);
            }
        }
        INSTANCE.machineLeases.clear();
        INSTANCE.captureLeases.clear();
    }

    public void cancelAllForPlayer(UUID playerId) {
        cancelAllForPlayer(playerId, "Player disconnected");
    }

    public void cancelAllForPlayer(UUID playerId, String reason) {
        List<AsyncCraftChain> toAbort = new ArrayList<>();
        for (AsyncCraftChain chain : activeChains.snapshot()) {
            if (chain.belongsTo(playerId)) toAbort.add(chain);
        }
        for (AsyncCraftChain chain : toAbort) {
            chain.abort(reason);
            RSIntegrationMod.LOGGER.debug("[RSI-AsyncMgr] Cancelled chain for player {}: {}", playerId, reason);
        }
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        MinecraftServer server = event.getServer();
        if (server == null || server.getPlayerList() == null) return;

        long tickStart = System.nanoTime();

        for (AsyncCraftChain chain : activeChains.snapshot()) {
            try {
                chain.tick();
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.error("[RSI-AsyncMgr] Chain tick error", e);
                String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                chain.abort("Internal error: " + msg);
                activeChains.remove(chain.getCraftId());
            }
        }
        PerformanceMonitor.recordTick(
                System.nanoTime() - tickStart);
    }
}
