package com.huanghuang.rsintegration.crafting.batch;
import java.lang.reflect.Method;

import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import com.huanghuang.rsintegration.crafting.graph.DemandRole;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
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

    /**
     * Match a physically produced stack against a recipe-output template.
     * Runtime NBT (enchantments, durability, capabilities) may legitimately be
     * added by the machine, so production auditing compares item identity only.
     */
    static boolean matchesProducedItem(@Nullable ItemStack actual, @Nullable ItemStack expected) {
        return actual != null && expected != null
                && !actual.isEmpty() && !expected.isEmpty()
                && ItemStack.isSameItem(actual, expected);
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

    record PreparationResult(PreparationState state, String detail,
                             @Nullable Component userMessage) {
        public PreparationResult(PreparationState state, String detail) {
            this(state, detail, null);
        }

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

        public static PreparationResult retry(String detail, Component userMessage) {
            return new PreparationResult(PreparationState.RETRY, detail, userMessage);
        }

        public static PreparationResult fatal(String detail) {
            return new PreparationResult(PreparationState.FATAL, detail);
        }

        public static PreparationResult fatal(String detail, Component userMessage) {
            return new PreparationResult(PreparationState.FATAL, detail, userMessage);
        }
    }

    default String describeExecutionState() {
        return "machine_state=unavailable";
    }

    /** Number of repeated flat-plan operations this delegate can execute as one physical batch. */
    default int prepareFlatBatch(int remainingOperations) {
        return remainingOperations > 0 ? 1 : 0;
    }

    /**
     * Maximum logical operations admitted to one flat dispatch after this
     * delegate has inspected its physical machine. Most machines retain the
     * configured server-work bound. A delegate may raise it only to expose one
     * bounded native machine batch that it processes under a single operation
     * lease, such as a multi-lane furnace factory.
     */
    default int flatBatchOperationLimit(int configuredLimit) {
        return Math.max(1, configuredLimit);
    }

    /** Whether flat multi-machine dispatch should probe prepared delegates for a larger native batch. */
    default boolean expandsFlatBatchOperationLimit() {
        return false;
    }

    /** Announces the aggregate execution count before graph output capture is armed. */
    default void prepareGraphBatch(int executions) {
    }

    /** Announces the total number of operations in the surrounding graph node. */
    default void prepareOperationCount(int totalOperations) {
    }

    /**
     * Preferred number of recipe executions assigned to one physical worker start.
     * The default preserves the one-operation transaction used by ordinary machines.
     * Delegates opting in must accept aggregated material counts and report the
     * matching aggregate expected output after {@link #prepareGraphBatch(int)}.
     */
    default int preferredParallelBatchSize(int totalOperations, int workerCount) {
        return 1;
    }

    /**
     * Opt in only when one physical start accepts one reusable input together
     * with several operations' consumables and returns the reusable input once.
     */
    default boolean supportsReusableBatchAggregation() {
        return false;
    }

    /**
     * Explicit opt-in for machines that can preload multiple operations while
     * still consuming one operation per processing cycle. Legacy delegates stay
     * on the existing one-by-one placement path.
     */
    default boolean supportsInputBuffer() {
        return false;
    }

    /**
     * Stable declaration for buffered multi-input and multi-output machines.
     * Existing delegates remain unbuffered until a machine has verified its
     * placement, autonomous processing, output capacity, and recovery rules.
     */
    @Nonnull
    default InputBufferContract inputBufferContract() {
        return InputBufferContract.none();
    }

    /**
     * Describe the physical input slots and output ports for one buffered start.
     * The chain must only call this after {@link #supportsInputBuffer()} returns
     * true; the default is deliberately disabled for compatibility.
     */
    default InputBufferPlan inputBufferPlan(int requestedOperations) {
        return InputBufferPlan.none();
    }

    /**
     * Start a physically laid-out buffered dispatch. Implementations must place
     * each input by {@link InputBufferPlan.InputSlot#slot()} and keep output
     * accounting by {@link InputBufferPlan.OutputPort#port()}.
     */
    @Deprecated
    default boolean tryStartWithInputBuffer(@Nonnull ServerPlayer player,
                                             @Nonnull InputBufferPlan plan,
                                             @Nonnull ExtractionLedger sharedLedger) {
        return false;
    }

    /**
     * Unified operation start entry. New delegates may override this method;
     * legacy delegates are adapted to their existing buffered, ordered-material,
     * or self-extracting entry point without changing behavior.
     */
    default boolean startOperation(@Nonnull OperationStartContext context) {
        if (context.repeatedOperationPlan() != null) return false;
        if (context.buffered()) {
            return tryStartWithInputBuffer(
                    context.player(), context.inputBufferPlan(), context.ledger());
        }
        List<ItemStack> materials = context.legacyMaterials();
        if (!materials.isEmpty()) {
            return tryStartWithMaterials(context.player(), materials, context.ledger());
        }
        return context.materialOwnership() == OperationStartContext.MaterialOwnership.DELEGATE_EXTRACTED
                ? tryStartSingleCraft(context.player())
                : tryStartSingleCraft(context.player(), context.ledger());
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

    /** Revalidate non-material requirements before commit and output publication. */
    default boolean validateExecutionContext(@Nullable ServerPlayer player) {
        return true;
    }

    /**
     * Optional one-shot preflight for outputs that require a storage capability
     * beyond ordinary item storage, such as a fluid disk. Returning a message
     * rejects the operation before materials are committed.
     */
    @Nullable
    default Component validateOutputStorage(@Nonnull ServerPlayer player) {
        return null;
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
    @Deprecated
    default boolean tryStartSingleCraft(@Nonnull ServerPlayer player) {
        return false;
    }

    /**
     * Variant that uses a shared ledger. The delegate should reserve from
     * {@code sharedLedger} instead of creating its own, and must NOT commit
     * (the chain commits once at the end). Default impl falls back to
     * creating a private ledger for backward compat.
     */
    @Deprecated
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
     * Optional exact material requirements for one prepared flat batch.
     * Returning a list means its counts already cover {@code executions}; the
     * chain must not scale them a second time. Returning null preserves the
     * legacy per-operation material contract.
     */
    @Nullable
    default List<IngredientSpec> getFlatBatchRequiredMaterials(int executions) {
        return null;
    }

    /**
     * Stable material declaration for new planning and start paths. Legacy
     * delegates are projected as graph-owned ordered entries until they opt in
     * with explicit allocation and physical slot identities.
     */
    @Nonnull
    default MaterialPlan materialPlan() {
        List<IngredientSpec> required = getRequiredMaterials();
        if (required == null || required.isEmpty()) return MaterialPlan.none();
        List<IngredientSpec> graph = getGraphSpecs();
        List<IngredientSpec> supplemental = getSupplementalSpecs();
        if ((graph == null || graph.isEmpty())
                && (supplemental == null || supplemental.isEmpty())
                && requiresPrivateLedgerGraphDispatch()) {
            return MaterialPlan.none();
        }
        try {
            return MaterialPlan.fromLegacyPartitions(required, graph, supplemental,
                    getMaterialReservationScopes());
        } catch (IllegalArgumentException ignored) {
            // A legacy delegate may recreate non-equal Ingredient instances on
            // each getter call. Preserve its former all-graph behavior until it
            // declares stable MaterialPlan entries explicitly.
            return MaterialPlan.fromLegacy(required, getMaterialReservationScopes());
        }
    }

    /**
     * Classifies required material slots for parallel execution. Reusable slots
     * are reserved once per worker and remain installed while that worker drains
     * its queued operations. Legacy delegates conservatively consume every slot
     * for every operation.
     */
    enum MaterialReservationScope {
        PER_OPERATION,
        PER_WORKER_REUSABLE
    }

    @Nonnull
    default List<MaterialReservationScope> getMaterialReservationScopes() {
        List<IngredientSpec> specs = getRequiredMaterials();
        if (specs == null || specs.isEmpty()) return List.of();
        return specs.stream()
                .map(spec -> spec.role() == DemandRole.CATALYST
                        ? MaterialReservationScope.PER_WORKER_REUSABLE
                        : MaterialReservationScope.PER_OPERATION)
                .toList();
    }

    /** Configure source preferences before this delegate's materials are reserved. */
    default void configureMaterialReservation(@Nonnull ExtractionLedger ledger,
                                              @Nonnull ServerPlayer player) {
    }

    /** Optional specific message when this delegate's material reservation fails. */
    @Nullable
    default Component materialReservationFailureMessage(@Nonnull ServerPlayer player) {
        return null;
    }

    /**
     * Return reusable materials that were intentionally left installed between
     * operations. Called once after a standalone node or parallel worker group
     * reaches successful terminal completion.
     */
    default void releaseReusableMaterials(@Nonnull ServerPlayer player) {}

    /**
     * The portion of {@link #getRequiredMaterials()} that the graph planner
     * should allocate via MaterialBroker checkout. Defaults to the full list
     * for delegates that do not override it.
     * <p>
     * Embers alchemy returns only tablet + inputs here; the missing aspects
     * are returned by {@link #getSupplementalSpecs()} and reserved directly
     * from the RS network without going through the graph.
     */
    @Nonnull
    default List<IngredientSpec> getGraphSpecs() {
        List<IngredientSpec> full = getRequiredMaterials();
        return full != null ? full : Collections.emptyList();
    }

    /**
     * Materials that bypass the graph planner and are reserved directly from
     * the RS network after (or alongside) graph reservation. Used by Embers
     * alchemy for aspect catalysts, which the planner deliberately omits.
     * <p>
     * Return {@code Collections.emptyList()} when there are no supplemental
     * materials (the default). When non-empty, {@code AsyncCraftChain} will
     * extract these from the network independently of the graph checkout.
     */
    @Nonnull
    default List<IngredientSpec> getSupplementalSpecs() {
        return List.of();
    }

    /**
     * Whether graph dispatch must call {@link #tryStartSingleCraft(ServerPlayer)}
     * with the delegate's private extraction ledger. This is required by
     * machines that consume inputs across multiple internal runs and cannot
     * safely accept one shared graph checkout.
     */
    default boolean requiresPrivateLedgerGraphDispatch() {
        return false;
    }

    /**
     * Merge graph-allocated and directly reserved supplemental materials into
     * the order expected by {@link #tryStartWithMaterials}. Delegates exposing
     * supplemental specs must override this method when concatenation is not
     * their placement order.
     */
    @Nonnull
    default List<ItemStack> mergeSupplementalMaterials(
            @Nonnull List<ItemStack> graphMaterials,
            @Nonnull List<ItemStack> supplementalMaterials) {
        if (supplementalMaterials.isEmpty()) return graphMaterials;
        List<ItemStack> merged = new ArrayList<>(
                graphMaterials.size() + supplementalMaterials.size());
        merged.addAll(graphMaterials);
        merged.addAll(supplementalMaterials);
        return merged;
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
    @Deprecated
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
    @Deprecated
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

    /** True when a FAILED observation happened after the operation consumed its inputs. */
    default boolean failureConsumesInputs(@Nonnull CraftObservation observation) {
        return false;
    }

    /**
     * Exact physical inputs removed during failure cleanup. A null result means
     * the delegate has not implemented recovery accounting; an empty list means
     * cleanup was audited and recovered nothing.
     */
    @Nullable
    default List<ItemStack> failureRecoveredInputs() {
        return null;
    }

    /** Optional translated reason for a terminal craft failure. */
    @Nullable
    default Component craftFailureMessage(@Nonnull CraftObservation observation) {
        return null;
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
     * Explicit per-operation output declaration for structured accounting.
     * Returning {@link OutputContract#none()} preserves the legacy collection
     * path until a delegate has declared every primary and secondary channel.
     */
    @Nonnull
    default OutputContract outputContract() {
        return OutputContract.none();
    }

    /**
     * Removes and identifies outputs that originate from a machine slot or a
     * virtual operation. World outputs captured by the chain are attached by
     * the orchestration layer because their entity origin is external to the
     * delegate's inventory.
     */
    @Nonnull
    default List<OutputAccounting.CollectedOutput> collectStructuredResults(
            @Nonnull ServerPlayer player) {
        return List.of();
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

    /**
     * Whether graph output declarations describe physical item stacks that this
     * operation must publish. Return {@code false} for recipes whose displayed
     * result is only a UI handle for a world-state change, such as upgrading an
     * already placed machine.
     */
    default boolean publishesDeclaredGraphOutputs() {
        return true;
    }

    /** Release resources acquired during preparation when this candidate is discarded. */
    default void releasePreparationResources() {
    }

    /** Cleanup and refund on batch failure. */
    void onBatchFailed(@Nonnull ServerPlayer player, @Nonnull String reason);

    /**
     * Cleanup on successful batch completion.
     *
     * <p>{@code player} is null when the craft finished but its owner is already
     * offline (server stop). Implementations must not dereference it — pass it
     * through to the null-tolerant refund helpers instead. Settling still has to
     * happen in that case, otherwise the ledger refunds inputs for a craft whose
     * product was already produced.</p>
     */
    void onBatchFinished(@Nullable ServerPlayer player);

    /** The machine position this delegate is operating on. */
    @Nonnull
    BlockPos getMachinePos();

    /**
     * Physical machine position used for operation leases. A binding may point at
     * a support block, such as a Botania catalyst below the actual Mana Pool.
     */
    default BlockPos getOperationMachinePos(@Nonnull BlockPos boundPos) {
        BlockPos machinePos = getMachinePos();
        return (machinePos == null ? boundPos : machinePos).immutable();
    }

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
    default AABB getOutputCaptureRegion() {
        return null;
    }

    /**
     * Whether capture regions from different machine origins may overlap.
     * The interceptor assigns a newborn item to the nearest region. Keep this
     * false unless the delegate's output position is tied to its machine.
     */
    default boolean allowsOverlappingOutputCaptureOrigins() {
        return false;
    }

    /**
     * True when {@link #collectAllResults(ServerPlayer)} can authoritatively
     * remove the completed product from a machine slot even though a defensive
     * world-output capture is also armed. World-spawning delegates remain false
     * so a premature DONE observation cannot lose their output.
     */
    default boolean canCollectResultWithoutWorldCapture() {
        return false;
    }
}
