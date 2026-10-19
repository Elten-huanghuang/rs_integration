package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.graph.CaptureLeaseRegistry;
import com.huanghuang.rsintegration.crafting.graph.MachineLeaseRegistry;
import com.huanghuang.rsintegration.crafting.graph.OperationBudget;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.network.binding.AltarBindingRegistry;
import com.huanghuang.rsintegration.command.PerformanceMonitor;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import java.util.concurrent.ConcurrentLinkedQueue;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Drives all active {@link AsyncCraftChain} instances every server tick
 * and cleans up chains owned by a player who disconnects.
 */
public final class AsyncCraftManager {

    private static final AsyncCraftManager INSTANCE = new AsyncCraftManager();
    private final ActiveCraftRegistry<UUID, AsyncCraftChain> activeChains = new ActiveCraftRegistry<>();
    private OperationServices operationServices = new OperationServices();
    private int roundRobinCursor;
    private final ConcurrentLinkedQueue<Runnable> completionQueue = new ConcurrentLinkedQueue<>();
    @Nullable
    private CraftTickWindow tickWindow;

    void enqueueCompletion(Runnable callback) {
        if (callback != null) completionQueue.add(callback);
    }

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

    /**
     * 注册后立即推进。纯工作台任务可在当前服务器 tick 内完成；
     * 预算耗尽、机器等待或结算跨 tick 时保留为活动链。
     * 必须在服务器线程调用。
     */
    public boolean submitAndAdvance(AsyncCraftChain chain, MinecraftServer server) {
        Objects.requireNonNull(chain, "chain");
        Objects.requireNonNull(server, "server");
        submit(chain);
        tickChain(chain, beginTickWindow(server));
        return chain.isDone();
    }

    public boolean hasActiveChainFor(UUID playerId) {
        return getChain(playerId) != null;
    }

    public int getActiveChainCount() {
        return activeChains.size();
    }

    MachineLeaseRegistry machineLeases() {
        return operationServices.machines();
    }

    /** True only when every supplied execution machine is leased by an active RSI operation. */
    public boolean areAllMachinesLeased(List<AltarBindingRegistry.BoundMachine> machines,
                                        String modTypeId) {
        if (machines == null || machines.isEmpty()) return false;
        MachineLeaseRegistry leases = operationServices.machines();
        for (AltarBindingRegistry.BoundMachine machine : machines) {
            MachineLeaseRegistry.MachineKey key = new MachineLeaseRegistry.MachineKey(
                    machine.dim(), machine.pos(), modTypeId);
            if (!leases.isLeased(key)) return false;
        }
        return true;
    }

    CaptureLeaseRegistry captureLeases() {
        return operationServices.captures();
    }

    OperationBudget operationBudget() {
        return operationServices.globalBudget();
    }

    OperationResourceCoordinator operationResources() {
        return operationServices.resources();
    }

    OperationExecutionKernel operationKernel() {
        return operationServices.kernel();
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
                chain.abortForServerStop();
            } catch (Exception | LinkageError e) {
                RSIntegrationMod.LOGGER.error("[RSI-AsyncMgr] Failed to abort chain during shutdown for player {}",
                        chain.getPlayerId(), e);
            }
        }
        OperationServices retired = INSTANCE.operationServices;
        OperationServices.Audit audit = retired.audit(INSTANCE.activeChains.size());
        if (!audit.clean()) {
            RSIntegrationMod.LOGGER.error(
                    "[RSI-AsyncMgr] Shutdown audit incomplete: crafts={} operations={} machines={} captures={} zones={} machineOwners={} captureOwners={}",
                    audit.activeCrafts(), audit.activeOperations(), audit.machineLeases(),
                    audit.captureLeases(), audit.captureZones(), retired.machines().snapshot(),
                    retired.captures().snapshot());
        } else {
            RSIntegrationMod.LOGGER.info("[RSI-AsyncMgr] Shutdown audit complete: no active ownership remains");
        }
        // Retire the whole generation so stale permits can never mutate the next
        // integrated/dedicated server's operation accounting.
        INSTANCE.operationServices = new OperationServices();
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
            if ("Player disconnected".equals(reason)) chain.abortOffline(reason);
            else chain.cancel(reason, Component.translatable("rsi.async.abort.player_cancelled"));
            RSIntegrationMod.LOGGER.debug("[RSI-AsyncMgr] Cancelled chain for player {}: {}", playerId, reason);
        }
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        MinecraftServer server = event.getServer();
        if (server == null || server.getPlayerList() == null) return;

        CraftTickWindow window = beginTickWindow(server);
        long tickStart = System.nanoTime();
        List<AsyncCraftChain> snapshot = activeChains.snapshot();
        int perChainLimit = configuredPerChainVanillaLimit();
        int deferredChains = 0;
        int start = snapshot.isEmpty() ? 0 : Math.floorMod(roundRobinCursor, snapshot.size());
        int processed = 0;
        for (int offset = 0; offset < snapshot.size(); offset++) {
            if (!window.hasTime()) {
                deferredChains += snapshot.size() - offset;
                break;
            }
            AsyncCraftChain chain = snapshot.get((start + offset) % snapshot.size());
            boolean completed = tickChain(chain, window);
            if (!completed && chain.isWaitingForVanillaBudget()
                    && window.remainingVanillaOperations(
                    chain.getCraftId(), perChainLimit) == 0) {
                deferredChains++;
            }
            processed++;
        }
        if (!snapshot.isEmpty()) {
            roundRobinCursor = nextRoundRobinCursor(start, processed, snapshot.size());
        }
        int callbacksRemaining = configuredCompletionCallbacksPerTick();
        for (Runnable callback; callbacksRemaining > 0
                && window.hasTime()
                && (callback = completionQueue.poll()) != null; callbacksRemaining--) {
            try { callback.run(); }
            catch (RuntimeException | LinkageError e) { RSIntegrationMod.LOGGER.error("[RSI-AsyncMgr] Completion callback failed", e); }
        }
        PerformanceMonitor.recordTick(
                System.nanoTime() - tickStart);
        if (!snapshot.isEmpty()) {
            PerformanceMonitor.recordVanillaTickBudget(
                    window.usedVanillaOperations(), window.vanillaOperationLimit(), deferredChains);
        }
    }

    private boolean tickChain(AsyncCraftChain chain, CraftTickWindow window) {
        if (window.alreadyAdvanced(chain.getCraftId())) {
            return chain.isDone();
        }
        int perChainLimit = configuredPerChainVanillaLimit();
        int maxPasses = Math.max(2, chain.stepsCount() + 2);
        boolean firstPass = true;
        for (int pass = 0; pass < maxPasses && !chain.isDone(); pass++) {
            if (!window.hasTime()) break;
            int remaining = window.remainingVanillaOperations(
                    chain.getCraftId(), perChainLimit);
            if (!firstPass && !chain.canAdvanceImmediately(remaining > 0)) break;
            VanillaCraftingTickBudget.ChainAllowance allowance =
                    window.allowance(chain.getCraftId(), perChainLimit);
            try {
                chain.tick(allowance);
            } catch (Exception exception) {
                RSIntegrationMod.LOGGER.error("[RSI-AsyncMgr] Chain tick error", exception);
                String message = exception.getMessage() == null
                        ? exception.getClass().getSimpleName() : exception.getMessage();
                try {
                    chain.abort("Internal error: " + message,
                            Component.translatable("rsi.async.abort.internal_error", message));
                } catch (Exception | LinkageError terminationError) {
                    RSIntegrationMod.LOGGER.error(
                            "[RSI-AsyncMgr] Chain termination also failed; keeping craft {} registered for audit",
                            chain.getCraftId(), terminationError);
                }
            } finally {
                window.markAdvanced(chain.getCraftId());
                window.record(chain.getCraftId(), allowance.used());
            }
            firstPass = false;
        }
        if (chain.isDone()) {
            activeChains.remove(chain.getCraftId());
        }
        return chain.isDone();
    }

    private synchronized CraftTickWindow beginTickWindow(MinecraftServer server) {
        long serverTick = server.getTickCount();
        if (tickWindow == null || tickWindow.serverTick() != serverTick) {
            tickWindow = new CraftTickWindow(serverTick, System.nanoTime(),
                    configuredServerTickBudgetNanos(), configuredGlobalVanillaLimit());
        }
        return tickWindow;
    }

    private static int configuredPerChainVanillaLimit() {
        try {
            return Math.max(1, RSIntegrationConfig
                    .CRAFTING_VANILLA_OPERATIONS_PER_TICK.get());
        } catch (Exception ignored) {
            return RSIntegrationConfig
                    .DEFAULT_CRAFTING_VANILLA_OPERATIONS_PER_TICK;
        }
    }

    private static int configuredGlobalVanillaLimit() {
        try {
            return Math.max(1, RSIntegrationConfig
                    .CRAFTING_GLOBAL_VANILLA_OPERATIONS_PER_TICK.get());
        } catch (Exception ignored) {
            return RSIntegrationConfig
                    .DEFAULT_CRAFTING_GLOBAL_VANILLA_OPERATIONS_PER_TICK;
        }
    }

    private static long configuredServerTickBudgetNanos() {
        try {
            return Math.max(1L, RSIntegrationConfig.CRAFTING_SERVER_TICK_BUDGET_MS.get())
                    * 1_000_000L;
        } catch (Exception ignored) {
            return RSIntegrationConfig.DEFAULT_CRAFTING_SERVER_TICK_BUDGET_MS * 1_000_000L;
        }
    }

    private static int configuredCompletionCallbacksPerTick() {
        try {
            return Math.max(1, RSIntegrationConfig.CRAFTING_COMPLETION_CALLBACKS_PER_TICK.get());
        } catch (Exception ignored) {
            return RSIntegrationConfig.DEFAULT_CRAFTING_COMPLETION_CALLBACKS_PER_TICK;
        }
    }

    static <T> List<T> roundRobinOrder(List<T> source, int start) {
        if (source.isEmpty()) return List.of();
        int normalized = Math.floorMod(start, source.size());
        List<T> ordered = new ArrayList<>(source.size());
        for (int offset = 0; offset < source.size(); offset++) {
            ordered.add(source.get((normalized + offset) % source.size()));
        }
        return List.copyOf(ordered);
    }

    static int nextRoundRobinCursor(int start, int processed, int size) {
        if (size <= 0) return 0;
        int normalizedStart = Math.floorMod(start, size);
        int boundedProcessed = Math.max(0, Math.min(processed, size));
        // A complete pass rotates the first chain. A partial pass resumes at the
        // first chain that did not receive a tick, including a zero-work pass.
        int advance = boundedProcessed == size ? 1 : Math.max(1, boundedProcessed);
        return (normalizedStart + advance) % size;
    }
}
