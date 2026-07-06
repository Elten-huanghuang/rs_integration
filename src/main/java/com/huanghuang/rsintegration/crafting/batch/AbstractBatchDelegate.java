package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.refinedmods.refinedstorage.api.network.INetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;

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
    public final boolean isCraftComplete(ServerLevel level) {
        BlockPos pos = getMachinePos();
        if (!level.isLoaded(pos)) return false;
        BlockEntity be = level.getBlockEntity(pos);
        if (be == null || be.isRemoved()) return true; // machine gone → treat as done
        return isMachineCraftFinished(level, be);
    }

    /**
     * Machine-specific completion check. Called by {@link #isCraftComplete(ServerLevel)}
     * after the chunk-load and null/removed guards. Both {@code level} and {@code be}
     * are guaranteed non-null and loaded.
     */
    protected boolean isMachineCraftFinished(ServerLevel level, BlockEntity be) {
        return false;
    }

    /**
     * Batch-failure handler with shared-ledger guard.
     * Subclasses must NOT override this; override {@link #clearMachineState} instead.
     */
    @Override
    public final void onBatchFailed(ServerPlayer player, String reason) {
        if (usingSharedLedger) return;
        BlockPos pos = getMachinePos();
        if (!player.level().isLoaded(pos)) return;
        BlockEntity be = player.level().getBlockEntity(pos);
        if (be != null) clearMachineState(be, player);
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
        ledger = null;
        sharedLedger = null;
        network = null;
        usingSharedLedger = false;
        seenWarnStates.clear();
    }
}
