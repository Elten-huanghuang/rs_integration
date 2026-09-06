package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.crafting.MaterialMatcher;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.CraftStorageEndpoint;
import com.huanghuang.rsintegration.crafting.CraftStorageEndpoints;
import com.huanghuang.rsintegration.util.ForcedChunkTicketManager;
import com.refinedmods.refinedstorage.api.network.INetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Shared base for all batch-craft delegates.
 *
 * <p>Provides template methods with lifecycle guards that subclasses must not override:</p>
 * <ul>
 *   <li>{@link #isCraftComplete(ServerLevel)} — {@code final}, with {@code isLoaded} guard</li>
 *   <li>{@link #onBatchFailed(ServerPlayer, String)} — {@code final}, with shared-ledger guard</li>
 * </ul>
 *
 * <p>Subclasses override the hook methods instead:</p>
 * <ul>
 *   <li>{@link #isMachineCraftFinished(ServerLevel, BlockEntity)} — completion check</li>
 *   <li>{@link #clearMachineState(BlockEntity, ServerPlayer)} — machine cleanup</li>
 * </ul>
 *
 * <p>{@link #warnOnce(String, String, Object...)} provides tick-safe rate-limited logging
 * via a {@link HashSet} of seen state keys, cleared on {@link #resetState()}.</p>
 */
public abstract class AbstractBatchDelegate implements IBatchDelegate {

    protected ExtractionLedger ledger;
    protected ExtractionLedger sharedLedger;
    protected INetwork network;
    /** Backend-neutral view of {@link #network}; populated lazily for legacy delegates. */
    @Nullable
    protected CraftStorageEndpoint storageEndpoint;
    protected boolean usingSharedLedger;
    protected CraftPhase phase = CraftPhase.WAITING_FOR_START;

    /**
     * Set by the first terminal callback ({@code onBatchFailed} or
     * {@code onBatchFinished}). Deliberately NOT cleared by {@link #resetState()}:
     * a delegate instance owns exactly one operation, so the flag must outlive
     * the state reset that its own cleanup performs.
     */
    private boolean terminalCleanupDone;
    /** True only when a loaded machine was actually given its failure cleanup hook. */
    private boolean physicalFailureCleanupCompleted;
    @Nullable
    private List<net.minecraft.world.item.ItemStack> failureRecoveredInputs;

    /**
     * A forced-chunk ticket is owned by the delegate operation, not by the
     * machine block entity. Keep its identity here so failure cleanup can
     * release it even when the block entity disappeared.
     */
    @Nullable
    private ServerLevel forcedChunkLevel;
    @Nullable
    private BlockPos forcedChunkOwner;
    private boolean machineChunkForced;

    /** The dimension the target machine lives in. Set by {@link #validateAndInit}. */
    @Nullable
    protected ResourceLocation machineDim;

    /** Fallback server reference for dimension resolution when player is offline. */
    @Nullable
    protected net.minecraft.server.MinecraftServer machineServer;

    /**
     * The concrete output the player asked for, captured from the JEI ghost
     * output slot at click time and threaded through the async chain. Non-null
     * only when the client supplied it. Lets a delegate distinguish between
     * outputs that share one recipe id but differ by NBT — e.g. WR arcane
     * iterator "Curse II" vs "Curse I" both resolve to the same recipe.
     */
    @Nullable
    protected net.minecraft.world.item.ItemStack targetOutput;

    private final Set<String> seenWarnStates = new HashSet<>();

    /**
     * Rate-limited warning — each distinct {@code state} key is logged at most once
     * per batch lifecycle. The set is cleared in {@link #resetState()}.
     * Safe against alternating error states (e.g. A→B→A won't re-log A).
     */
    protected void warnOnce(String state, String format, Object... args) {
        if (seenWarnStates.add(state)) {
            RSIntegrationMod.LOGGER.warn(format, args);
        }
    }

    // ── Template methods (final — subclasses override hooks instead) ──

    /**
     * Completion check with chunk-unload guard.
     * Subclasses must NOT override this; override {@link #isMachineCraftFinished} instead.
     */
    @Override
    public final boolean isCraftComplete(ServerLevel playerLevel) {
        BlockPos pos = getMachinePos();
        // Virtual delegate (e.g. GenericBatchDelegate for CUSTOM_GUI recipes)
        // has no physical machine — skip chunk-load and BE checks.
        if (pos == null) return isMachineCraftFinished(null, null);
        ServerLevel level = resolveMachineLevel(playerLevel);
        if (level == null) return false;
        if (!level.hasChunkAt(pos)) return false;
        BlockEntity be = level.getBlockEntity(pos);
        if (be == null || be.isRemoved()) return false;
        return isMachineCraftFinished(level, be);
    }

    @Nonnull
    @Override
    public final CraftObservation observeCraft(@Nonnull ServerLevel playerLevel) {
        BlockPos pos = getMachinePos();
        if (pos == null) {
            if (isMachineCraftFinished(null, null)) phase = CraftPhase.DONE;
            else if (phase == CraftPhase.WAITING_FOR_START) phase = CraftPhase.WORKING;
            return new CraftObservation(phase);
        }
        ServerLevel level = resolveMachineLevel(playerLevel);
        if (level == null) return failObservation("machine dimension unavailable");
        if (!level.hasChunkAt(pos)) return failObservation("machine chunk unloaded");
        BlockEntity be = level.getBlockEntity(pos);
        if (be == null || be.isRemoved()) return observeMissingMachineCraft(level, pos);
        CraftObservation observed = observeMachineCraft(level, be);
        if (observed != null) phase = observed.phase();
        return observed != null ? observed : new CraftObservation(phase);
    }

    /** Machine-specific observation when normal operation removes or replaces the block entity. */
    @Nonnull
    protected CraftObservation observeMissingMachineCraft(@Nonnull ServerLevel level, @Nonnull BlockPos pos) {
        return failObservation("machine block entity missing");
    }

    /** Machine-specific phase observation. New slot delegates should override. */
    @Nonnull
    protected CraftObservation observeMachineCraft(@Nonnull ServerLevel level, @Nonnull BlockEntity be) {
        // The first observation only establishes that the machine was seen after
        // placement. This prevents a pre-existing output or an already-empty input
        // slot from completing a craft before it has entered WORKING.
        if (phase == CraftPhase.WAITING_FOR_START) {
            phase = CraftPhase.WORKING;
            return new CraftObservation(phase);
        }
        if (isMachineCraftFinished(level, be)) phase = CraftPhase.DONE;
        return new CraftObservation(phase);
    }

    protected final void markCraftStarted() {
        phase = CraftPhase.WAITING_FOR_START;
    }

    protected final CraftObservation workingObservation() {
        phase = CraftPhase.WORKING;
        return new CraftObservation(phase);
    }

    protected final CraftObservation doneObservation() {
        phase = CraftPhase.DONE;
        return new CraftObservation(phase);
    }

    protected final CraftObservation failObservation(String detail) {
        phase = CraftPhase.FAILED;
        return new CraftObservation(phase, detail);
    }

    /**
     * Machine-specific completion check. Called by {@link #isCraftComplete(ServerLevel)}
     * after the chunk-load and null/removed guards. Both {@code level} and {@code be}
     * are guaranteed non-null and loaded.
     */
    protected boolean isMachineCraftFinished(@Nonnull ServerLevel level, @Nonnull BlockEntity be) {
        return false;
    }

    /**
     * Batch-failure handler.
     * Subclasses must NOT override this; override {@link #clearMachineState} instead.
     * When {@code player} is null (offline), resolves the machine level via
     * {@link #machineServer} and drops refund items in-world.
     * <p>
     * Always calls {@link #clearMachineState} even for shared-ledger delegates:
     * the chain's abort refunds the ledger, but the physical items on the machine
     * still need to be removed. The {@code clearMachineState} implementations
     * already guard against double-refund via {@code usingSharedLedger} /
     * {@code refundToRS} checks.
     */
    @Override
    public final void onBatchFailed(@Nullable ServerPlayer player, String reason) {
        // Exactly-once. A second call would be actively harmful, not merely
        // redundant: clearMachineState ends in resetState(), which clears
        // usingSharedLedger, so the repeat would see the shared-ledger guard
        // disarmed and refund physical items the chain ledger already covered.
        if (!markTerminalCleanup()) {
            RSIntegrationMod.LOGGER.warn(
                    "[RSI-Delegate] {} received a repeat terminal callback (onBatchFailed: {}); ignoring. "
                            + "A delegate instance owns exactly one operation and must be discarded after it settles.",
                    getClass().getSimpleName(), reason);
            return;
        }
        physicalFailureCleanupCompleted = false;
        failureRecoveredInputs = null;
        try {
            releasePreparationResources();
            BlockPos pos = getMachinePos();
            // Virtual delegates (e.g. GenericBatchDelegate for CUSTOM_GUI recipes)
            // have no physical machine - nothing to clean up.
            if (pos == null) return;
            ServerLevel level = resolveMachineLevel(player != null ? player.serverLevel() : null);
            if (level == null) return;
            if (!level.hasChunkAt(pos)) {
                RSIntegrationMod.LOGGER.warn(
                        "[RSI-Delegate] Skipping physical cleanup for unloaded machine chunk at {} in {}; "
                                + "the transaction ledger remains authoritative",
                        pos, level.dimension().location());
                return;
            }
            BlockEntity be = level.getBlockEntity(pos);
            if (be != null) {
                clearMachineState(be, player);
                physicalFailureCleanupCompleted = failureRecoveredInputs != null
                        || isFailureRefundSafe();
            } else {
                clearMissingMachineState(player);
                physicalFailureCleanupCompleted = failureRecoveredInputs != null;
            }
        } finally {
            releaseMachineChunk();
        }
    }

    /**
     * Resolve the {@link ServerLevel} where the target machine lives.
     * Falls back to the player's current level when {@link #machineDim} is not set
     * (e.g. virtual delegates like Market that have no physical machine).
     * When {@code playerLevel} is null (player offline), uses {@link #machineServer}.
     */
    protected ServerLevel resolveMachineLevel(@Nullable ServerLevel playerLevel) {
        if (machineDim == null) return playerLevel;
        try {
            net.minecraft.server.MinecraftServer server;
            if (playerLevel != null) {
                server = playerLevel.getServer();
            } else {
                server = this.machineServer;
            }
            if (server == null) return playerLevel;
            var key = ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, machineDim);
            ServerLevel resolved = server.getLevel(key);
            return resolved != null ? resolved : playerLevel;
        } catch (Exception e) {
            return playerLevel;
        }
    }

    /** Convenience overload: resolve machine level from a player reference. */
    protected ServerLevel resolveMachineLevel(@Nullable ServerPlayer player) {
        return resolveMachineLevel(player != null ? player.serverLevel() : null);
    }

    /** Exposed for {@code ParallelCraftGroup} and chain-level dimension resolution. */
    @Nullable
    public ResourceLocation getMachineDim() {
        return machineDim;
    }

    /** Mark this delegate as using the chain-owned committed ledger. */
    public final void useSharedLedger(@Nonnull ExtractionLedger sharedLedger) {
        this.sharedLedger = sharedLedger;
        CraftStorageEndpoint selected = sharedLedger.storageEndpoint();
        if (selected != null) this.storageEndpoint = selected;
        this.usingSharedLedger = true;
    }

    /** Attach the chain-selected storage backend before private-ledger starts. */
    public final void setStorageEndpoint(@Nullable CraftStorageEndpoint endpoint) {
        this.storageEndpoint = endpoint;
    }

    /** Called by the chain after {@link #validateAndInit} succeeds. */
    public void setMachineDim(@Nonnull ResourceLocation dim) {
        this.machineDim = dim;
    }

    /** Fallback server for dimension resolution when the player is offline. */
    public void setMachineServer(@Nonnull net.minecraft.server.MinecraftServer server) {
        this.machineServer = server;
    }

    /**
     * Default capture box for the output interceptor: a tight cube around the
     * machine centre. The interceptor now uses position-only matching + newborn
     * filtering ({@code tickCount == 0}), so false captures are naturally rare;
     * the AABB just needs to cover the machine's typical output spawn point.
     * Delegates whose output drops at a known offset (e.g. TLM altar 2 blocks
     * up) should override this with a precise box.
     * <p>
     * Returns null for virtual delegates with no physical machine.
     */
    @Override
    public AABB getOutputCaptureRegion() {
        BlockPos pos = getMachinePos();
        return pos == null ? null : new AABB(pos).inflate(1.5);
    }

    /**
     * Set the concrete output the player asked for (from the JEI ghost slot).
     * Called by the chain after {@link #validateAndInit} succeeds, mirroring
     * {@link #setMachineDim}. Delegates that don't need it simply ignore it.
     */
    public void setTargetOutput(@Nullable net.minecraft.world.item.ItemStack target) {
        this.targetOutput = target != null && !target.isEmpty() ? target.copy() : null;
    }

    /** The concrete output the player asked for, or null if none was supplied. */
    @Nullable
    public net.minecraft.world.item.ItemStack getTargetOutput() {
        return targetOutput;
    }

    /**
     * Machine-specific cleanup. Called by {@link #onBatchFailed(ServerPlayer, String)}
     * after the shared-ledger and chunk-load guards.
     */
    protected void clearMachineState(BlockEntity be, ServerPlayer player) {
        // no-op; subclasses with inventory-based machines should override
    }

    /**
     * Whether failure cleanup recovered or invalidated every physical input that
     * the shared ledger would refund. Inventory delegates should return false
     * when an expected input disappeared before cleanup.
     */
    protected boolean isFailureRefundSafe() {
        return false;
    }

    /** Record the exact stacks physically removed by an audited cleanup hook. */
    protected final void recordFailureRecoveredInputs(
            @Nonnull List<net.minecraft.world.item.ItemStack> recovered) {
        java.util.ArrayList<net.minecraft.world.item.ItemStack> copy = new java.util.ArrayList<>();
        for (net.minecraft.world.item.ItemStack stack : recovered) {
            if (stack != null && !stack.isEmpty()) copy.add(stack.copy());
        }
        failureRecoveredInputs = List.copyOf(copy);
    }

    @Nullable
    @Override
    public final List<net.minecraft.world.item.ItemStack> failureRecoveredInputs() {
        return failureRecoveredInputs;
    }

    /** Release delegate-owned state when the prepared block entity no longer exists. */
    protected void clearMissingMachineState(@Nullable ServerPlayer player) {
    }

    // ── Shared state lifecycle ─────────────────────────────────────

    /**
     * Marks this delegate's operation as terminally settled and reports whether
     * the caller is the first to do so.
     *
     * <p>{@code onBatchFailed} is guarded centrally because it is {@code final}.
     * {@code onBatchFinished} is implemented per subclass, so a subclass whose
     * completion path is not naturally idempotent should gate on this:
     * {@code if (!markTerminalCleanup()) return;}</p>
     */
    protected final boolean markTerminalCleanup() {
        if (terminalCleanupDone) return false;
        terminalCleanupDone = true;
        return true;
    }

    /** True once a terminal callback has run for this operation. */
    protected final boolean isTerminalCleanupDone() {
        return terminalCleanupDone;
    }

    /**
     * Returns the storage boundary for this delegate. The lazy bridge keeps
     * existing machine initialization code source-compatible while allowing
     * refund/output paths to stop calling the RS API directly.
     */
    @Nullable
    protected final CraftStorageEndpoint storageEndpoint() {
        if (storageEndpoint == null && network != null) {
            storageEndpoint = CraftStorageEndpoints.fromLegacyNetwork(network);
        }
        return storageEndpoint;
    }

    /** True when this operation has a selected backend, RS or otherwise. */
    protected final boolean hasStorageAccess() {
        return storageEndpoint() != null;
    }

    /** Insert into the selected storage backend and return any remainder. */
    @Nonnull
    protected final net.minecraft.world.item.ItemStack insertIntoStorage(
            @Nullable ServerPlayer player,
            @Nonnull net.minecraft.world.item.ItemStack stack,
            boolean simulate) {
        if (stack.isEmpty()) return net.minecraft.world.item.ItemStack.EMPTY;
        CraftStorageEndpoint endpoint = storageEndpoint();
        if (endpoint == null || player == null) return stack.copy();
        return endpoint.insert(player, stack, simulate).remainder().orElse(net.minecraft.world.item.ItemStack.EMPTY);
    }

    /** Extract an exact item count through the selected storage backend. */
    @Nonnull
    protected final net.minecraft.world.item.ItemStack extractExactFromStorage(
            @Nullable ServerPlayer player,
            @Nonnull net.minecraft.world.item.ItemStack template,
            int amount,
            boolean simulate) {
        if (player == null || amount <= 0) return net.minecraft.world.item.ItemStack.EMPTY;
        CraftStorageEndpoint endpoint = storageEndpoint();
        if (endpoint == null) return net.minecraft.world.item.ItemStack.EMPTY;
        var result = endpoint.extractExact(player, template, amount, simulate);
        net.minecraft.world.item.ItemStack combined = net.minecraft.world.item.ItemStack.EMPTY;
        for (net.minecraft.world.item.ItemStack extracted : result.extractedStacks()) {
            if (extracted == null || extracted.isEmpty()) continue;
            if (combined.isEmpty()) combined = extracted.copy();
            else if (MaterialMatcher.equivalentRuntimeFragment(combined, extracted)) {
                combined.grow(extracted.getCount());
            }
        }
        return combined;
    }

    /**
     * Whether failure cleanup reached a loaded machine and ran its inventory
     * cleanup hook. Graph execution uses this to distinguish recoverable
     * in-machine inputs from items that may still be inside an unavailable block.
     */
    public final boolean physicalFailureCleanupCompleted() {
        return physicalFailureCleanupCompleted;
    }

    /**
     * Acquire or release this operation's machine chunk ticket. Repeated calls
     * are idempotent, and release uses the exact level/owner captured at
     * acquisition time rather than mutable subclass fields.
     */
    protected final void forceMachineChunk(@Nullable ServerLevel level,
                                           @Nullable BlockPos owner,
                                           boolean load) {
        if (!load) {
            releaseMachineChunk();
            return;
        }
        if (level == null || owner == null) return;
        if (machineChunkForced) {
            if (forcedChunkLevel == level && owner.equals(forcedChunkOwner)) return;
            releaseMachineChunk();
            if (machineChunkForced) return;
        }

        // Never use a Forge ticket to revive an unloaded target. A ticket may
        // only keep an already-loaded machine chunk resident after start.
        if (!level.hasChunkAt(owner)) return;
        if (ForcedChunkTicketManager.retain(level, owner)) {
            forcedChunkLevel = level;
            forcedChunkOwner = owner.immutable();
            machineChunkForced = true;
        }
    }

    /** Release the exact ticket acquired by {@link #forceMachineChunk}. */
    protected final void releaseMachineChunk() {
        if (!machineChunkForced) return;
        ServerLevel level = forcedChunkLevel;
        BlockPos owner = forcedChunkOwner;
        if (level == null || owner == null) {
            clearForcedChunkState();
            return;
        }
        if (ForcedChunkTicketManager.release(level, owner)) {
            clearForcedChunkState();
        }
    }

    private void clearForcedChunkState() {
        machineChunkForced = false;
        forcedChunkLevel = null;
        forcedChunkOwner = null;
    }

    /** Call at end of onBatchFailed and onBatchFinished — resets all shared state. */
    protected void resetState() {
        releaseMachineChunk();
        if (ledger != null) {
            ledger.close();
        }
        ledger = null;
        sharedLedger = null;
        network = null;
        storageEndpoint = null;
        usingSharedLedger = false;
        machineDim = null;
        machineServer = null;
        targetOutput = null;
        phase = CraftPhase.WAITING_FOR_START;
        seenWarnStates.clear();
    }
}
