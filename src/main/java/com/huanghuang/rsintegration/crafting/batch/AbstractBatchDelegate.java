package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.refinedmods.refinedstorage.api.network.INetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.HashSet;
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
    protected boolean usingSharedLedger;

    /** The dimension the target machine lives in. Set by {@link #validateAndInit}. */
    @Nullable
    protected ResourceLocation machineDim;

    /** Fallback server reference for dimension resolution when player is offline. */
    @Nullable
    protected net.minecraft.server.MinecraftServer machineServer;

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
        if (level == null || !level.isLoaded(pos)) return false;
        BlockEntity be = level.getBlockEntity(pos);
        if (be == null || be.isRemoved()) return true; // machine gone → treat as done
        return isMachineCraftFinished(level, be);
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
     * Batch-failure handler with shared-ledger guard.
     * Subclasses must NOT override this; override {@link #clearMachineState} instead.
     * When {@code player} is null (offline), resolves the machine level via
     * {@link #machineServer} and drops refund items in-world.
     */
    @Override
    public final void onBatchFailed(@Nullable ServerPlayer player, String reason) {
        if (usingSharedLedger) return;
        BlockPos pos = getMachinePos();
        ServerLevel level = resolveMachineLevel(player != null ? player.serverLevel() : null);
        if (level == null) return;
        // Force-load chunk if unloaded so physical items can always be recovered.
        // Without this, cross-dimension crafts lose items when the chunk unloads
        // before the chain aborts: the ledger gets refunded but the machine items
        // stay in NBT, then get consumed by vanilla mechanics on chunk reload.
        if (!level.isLoaded(pos)) {
            level.getChunk(pos);
        }
        BlockEntity be = level.getBlockEntity(pos);
        if (be != null) clearMachineState(be, player);
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

    /** Called by the chain after {@link #validateAndInit} succeeds. */
    public void setMachineDim(@Nonnull ResourceLocation dim) {
        this.machineDim = dim;
    }

    /** Fallback server for dimension resolution when the player is offline. */
    public void setMachineServer(@Nonnull net.minecraft.server.MinecraftServer server) {
        this.machineServer = server;
    }

    /**
     * Machine-specific cleanup. Called by {@link #onBatchFailed(ServerPlayer, String)}
     * after the shared-ledger and chunk-load guards.
     */
    protected void clearMachineState(BlockEntity be, ServerPlayer player) {
        // no-op; subclasses with inventory-based machines should override
    }

    // ── Shared state lifecycle ─────────────────────────────────────

    /** Call at end of onBatchFailed and onBatchFinished — resets all shared state. */
    protected void resetState() {
        if (ledger != null) {
            ledger.close();
        }
        ledger = null;
        sharedLedger = null;
        network = null;
        usingSharedLedger = false;
        machineDim = null;
        machineServer = null;
        seenWarnStates.clear();
    }
}
