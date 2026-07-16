package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;

public interface IBatchDelegate {

    /**
     * A delegate instance owns exactly one recipe operation. After either
     * {@link #onBatchFinished} or {@link #onBatchFailed}, callers must discard it
     * and create a new instance before starting another operation.
     */

    enum CraftPhase {
        WAITING_FOR_START,
        WORKING,
        DONE,
        FAILED
    }

    record CraftObservation(CraftPhase phase, String detail) {
        public CraftObservation(CraftPhase phase) {
            this(phase, "");
        }
    }

    record ExpectedProduction(ItemStack item, int count) {
        public ExpectedProduction {
            item = item == null ? ItemStack.EMPTY : item.copyWithCount(1);
            count = Math.max(0, count);
        }
    }

    enum PreparationState {
        READY,
        RETRY,
        FATAL
    }

    record PreparationResult(PreparationState state, String detail) {
        public PreparationResult {
            if (state == null) throw new IllegalArgumentException("preparation state is required");
            detail = detail == null ? "" : detail;
        }

        public static PreparationResult ready() {
            return new PreparationResult(PreparationState.READY, "");
        }

        public static PreparationResult retry(String detail) {
            return new PreparationResult(PreparationState.RETRY, detail);
        }

        public static PreparationResult fatal(String detail) {
            return new PreparationResult(PreparationState.FATAL, detail);
        }
    }

    boolean validateAndInit(@Nonnull ServerPlayer player, @Nonnull ResourceLocation recipeId,
                            @Nullable ResourceLocation dim, @Nonnull BlockPos pos);

    /**
     * Classify preparation without forcing callers to infer permanence from a boolean.
     * Legacy delegates remain conservatively retryable when validation returns false.
     */
    default PreparationResult prepare(@Nonnull ServerPlayer player, @Nonnull ResourceLocation recipeId,
                                      @Nullable ResourceLocation dim, @Nonnull BlockPos pos) {
        return validateAndInit(player, recipeId, dim, pos)
                ? PreparationResult.ready()
                : PreparationResult.retry("delegate validation did not accept the machine yet");
    }

    /**
     * Whether a bound block without a block entity is a valid idle form for this delegate.
     * Most machines require a block entity; world-interaction machines may opt in after
     * validating the concrete block at the bound position.
     */
    default boolean acceptsMachineWithoutBlockEntity(@Nonnull ServerLevel level, @Nonnull BlockPos pos) {
        return false;
    }

    /**
     * Check machine is idle, extract materials for one craft, place them, and start.
     * @return true if the craft was successfully started
     */
    boolean tryStartSingleCraft(@Nonnull ServerPlayer player);

    /**
     * Variant that uses a shared ledger. The delegate should reserve from
     * {@code sharedLedger} instead of creating its own, and must NOT commit
     * (the chain commits once at the end). Default impl falls back to
     * creating a private ledger for backward compat.
     */
    default boolean tryStartSingleCraft(@Nonnull ServerPlayer player, @Nonnull ExtractionLedger sharedLedger) {
        return tryStartSingleCraft(player);
    }

    /**
     * Return the ingredient specs for the recipe loaded by {@link #validateAndInit}.
     * Used by {@code AsyncCraftChain} to pre-reserve all multi-block materials
     * from a shared ledger before placing them in the machine.
     *
     * @return ingredient list in the order expected by {@link #tryStartWithMaterials},
     *         or null if the delegate handles extraction on its own
     */
    @Nullable
    default List<IngredientSpec> getRequiredMaterials() {
        return null;
    }

    /**
     * Structured safety contract for cross-node execution. Unknown or incomplete
     * contracts remain exclusive even when the legacy boolean returns true.
     */
    @Nullable
    default BatchConcurrencyCapabilities concurrencyCapabilities() {
        return null;
    }

    /**
     * Legacy opt-in retained for source compatibility. It is no longer sufficient
     * by itself to enable cross-node execution.
     */
    default boolean supportsConcurrentNodeExecution() {
        return false;
    }

    /**
     * Accept pre-reserved materials from the chain and start the craft.
     * Materials are in the order returned by {@link #getRequiredMaterials()}.
     * The delegate must place each stack in the correct machine slot and then
     * start the craft — it must NOT extract from RS or commit the ledger.
     *
     * @param player       the player who initiated the craft
     * @param materials    pre-reserved ItemStacks matching the spec order
     * @param sharedLedger the chain's master ledger (for refund reference only,
     *                     do NOT commit)
     * @return true if all materials were placed and the craft was started
     */
    default boolean tryStartWithMaterials(@Nonnull ServerPlayer player,
                                          @Nonnull List<ItemStack> materials,
                                          @Nonnull ExtractionLedger sharedLedger) {
        return false;
    }

    /**
     * Poll machine state to detect craft completion.
     * Called every tick while in WAITING state.
     */
    boolean isCraftComplete(@Nonnull ServerLevel level);

    /**
     * Observe the physical craft lifecycle. Legacy delegates are considered
     * working after start and map their old completion predicate to DONE.
     */
    @Nonnull
    default CraftObservation observeCraft(@Nonnull ServerLevel level) {
        return new CraftObservation(isCraftComplete(level) ? CraftPhase.DONE : CraftPhase.WORKING);
    }

    /**
     * Collect the result item from the machine after craft completes.
     * @return the result ItemStack, or ItemStack.EMPTY if not yet available
     */
    @Nonnull
    ItemStack collectResult(@Nonnull ServerPlayer player);

    /** Collect every real stack still owned by this craft. */
    @Nonnull
    default List<ItemStack> collectAllResults(@Nonnull ServerPlayer player) {
        ItemStack result = collectResult(player);
        return result.isEmpty() ? List.of() : List.of(result);
    }

    /** True when {@link #collectAllResults} already includes physical remainders/byproducts. */
    default boolean collectsPhysicalSecondaryOutputs() {
        return false;
    }

    /**
     * Expected item production for external-extraction detection. Return null
     * for entity, fluid, dynamic, or synchronous outputs that cannot be counted safely.
     * Counts are matched by item type; physical output keeps its real runtime NBT.
     */
    @Nullable
    default ExpectedProduction getExpectedProduction() {
        return null;
    }

    /** Cleanup and refund on batch failure. */
    void onBatchFailed(@Nonnull ServerPlayer player, @Nonnull String reason);

    /** Cleanup on successful batch completion. */
    void onBatchFinished(@Nonnull ServerPlayer player);

    /** The machine position this delegate is operating on. */
    @Nonnull
    BlockPos getMachinePos();

    /**
     * The concrete output this craft produces, used by {@code CraftOutputInterceptor}
     * to recognise the product the instant it drops as a world item entity.
     * <p>
     * Non-null only for delegates whose output spawns in the world (altar/crucible
     * types) and therefore needs protecting from other mods' magnets. Machine-slot
     * delegates leave this null and opt out of interception. Matching is by item
     * type only, so a bare recipe result is fine even for dynamic-NBT outputs.
     */
    @Nullable
    default ItemStack getExpectedOutput() {
        return null;
    }

    /**
     * The world box where {@link #getExpectedOutput} is expected to drop. Only
     * consulted when {@code getExpectedOutput()} is non-null. {@code AbstractBatchDelegate}
     * defaults it to a tight box around {@link #getMachinePos()}; delegates whose
     * output spawns at an offset (e.g. above the block) should override.
     */
    @Nullable
    default net.minecraft.world.phys.AABB getOutputCaptureRegion() {
        return null;
    }
}
