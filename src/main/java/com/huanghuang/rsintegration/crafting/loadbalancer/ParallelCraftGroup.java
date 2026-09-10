package com.huanghuang.rsintegration.crafting.loadbalancer;

import com.huanghuang.rsintegration.crafting.MaterialMatcher;
import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.ModVersionDelegateRegistry;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.CraftOutputInterceptor;
import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.OperationExecutionKernel;
import com.huanghuang.rsintegration.crafting.OperationResourceCoordinator;
import com.huanghuang.rsintegration.crafting.batch.AbstractBatchDelegate;
import com.huanghuang.rsintegration.crafting.batch.BatchConcurrencyCapabilities;
import com.huanghuang.rsintegration.crafting.graph.GraphConcurrencyPolicy;
import com.huanghuang.rsintegration.crafting.graph.MachineLeaseRegistry;
import com.huanghuang.rsintegration.crafting.graph.NodeId;
import com.huanghuang.rsintegration.crafting.graph.OperationBudget;
import com.huanghuang.rsintegration.crafting.batch.IBatchDelegate;
import com.huanghuang.rsintegration.crafting.batch.PreparationMessageScope;
import com.huanghuang.rsintegration.network.binding.AltarBindingRegistry.BoundMachine;
import com.huanghuang.rsintegration.recipe.ModRecipeHandlers;
import com.huanghuang.rsintegration.crafting.CraftStorageEndpoint;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.NotNull;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

/**
 * A dynamic pool of physical machines executing one recipe operation at a time.
 * A worker that finishes immediately collects its output and claims the next
 * operation without waiting for slower siblings.
 *
 * <p><b>THREAD SAFETY:</b> This class is <b>NOT</b> thread-safe.
 * All methods must be called from the server tick thread only.
 * Internal state (workers, settledResults, etc.) is not synchronized;
 * calling from worker threads will cause data races.
 */
public final class ParallelCraftGroup implements IBatchDelegate {

    private final List<WorkerSlot> workers = new ArrayList<>();
    private final java.util.Map<Integer, CraftOutputInterceptor.CaptureHandle> legacyCaptureHandles =
            new java.util.HashMap<>();
    private final List<ItemStack> settledResults = new ArrayList<>();
    private final ModType modType;
    private final ResourceLocation recipeId;
    private final BatchConcurrencyCapabilities concurrencyCapabilities;
    private final boolean inferMode;
    @Nullable
    private final CraftStorageEndpoint storageEndpoint;
    private final OperationQueue operations;
    private final boolean[] safelyRecoverableVirtual;
    private BlockPos representativePos = BlockPos.ZERO;
    private MinecraftServer machineServer;
    private ServerPlayer player;
    private ExtractionLedger sharedLedger;
    private List<List<ItemStack>> operationMaterials = List.of();
    private List<List<ItemStack>> virtualDebits = List.of();
    private List<List<ItemStack>> producerDebits = List.of();
    private List<ExtractionLedger.ReservationToken> reservationTokens = List.of();
    /** Reservations for reusable materials, grouped by physical worker. */
    private List<ExtractionLedger.ReservationToken> reusableReservationTokens = List.of();
    private boolean[] reusableReservationsSettled = new boolean[0];
    private List<IngredientSpec> baseSpecs;
    private List<IngredientSpec> graphSpecs = List.of();
    private List<IngredientSpec> supplementalSpecs = List.of();
    private ItemStack targetOutput;
    private OperationBudget craftOperationBudget;
    private OperationBudget globalOperationBudget;
    private OperationExecutionKernel operationKernel;
    private java.util.UUID craftId;
    private NodeId nodeId;
    private boolean sharedMaterialMode;
    private boolean started;
    private boolean draining;
    private boolean queuedMaterialsRecovered;
    private String failureDetail = "";
    private long lastProgressTick = -1;
    private long lastWaitReportTick = -1;
    private int lastObservedCompleted;
    private long lastObservedCaptured;
    private long nextWaitCheckTick;
    /** Whether every worker requiring physical cleanup was successfully cleaned. */
    private boolean physicalFailureCleanupCompleted;
    @Nullable
    private List<ItemStack> failureRecoveredInputs;
    /** Terminal state observed before failure cleanup clears worker operation ids. */
    @Nullable
    private OperationExecutionKernel.TerminalClass failureTerminalClass;

    private enum ChildPreparationState { READY, RETRY, FATAL }

    private record ChildPreparation(ChildPreparationState state,
                                    IBatchDelegate delegate,
                                    String detail) {
        static ChildPreparation ready(IBatchDelegate delegate) {
            return new ChildPreparation(ChildPreparationState.READY, delegate, "");
        }

        static ChildPreparation retry(String detail) {
            return new ChildPreparation(ChildPreparationState.RETRY, null, detail);
        }

        static ChildPreparation fatal(String detail) {
            return new ChildPreparation(ChildPreparationState.FATAL, null, detail);
        }
    }

    private static final class WorkerSlot {
        final int id;
        final BoundMachine machine;
        IBatchDelegate delegate;
        OperationExecutionKernel.Session operationSession;
        List<Integer> operationIds = List.of();
        boolean pristineDelegate = true;
        boolean hasStartedOperation;
        boolean needsFailureCleanup;
        CraftObservation lastObservation = new CraftObservation(CraftPhase.WAITING_FOR_START);

        WorkerSlot(int id, BoundMachine machine, IBatchDelegate delegate) {
            this.id = id;
            this.machine = machine;
            this.delegate = delegate;
        }

        boolean running() {
            return !operationIds.isEmpty();
        }

        int firstOperationId() {
            return running() ? operationIds.get(0) : -1;
        }

        void clearOperations() {
            operationIds = List.of();
        }
    }

    public ParallelCraftGroup(List<BoundMachine> machines, ModType modType,
                              ResourceLocation recipeId, ServerPlayer player,
                              int totalOperations) {
        this(machines, modType, recipeId, player, totalOperations, false, null);
    }

    public ParallelCraftGroup(List<BoundMachine> machines, ModType modType,
                              ResourceLocation recipeId, ServerPlayer player,
                              int totalOperations, boolean inferMode,
                              @Nullable BatchConcurrencyCapabilities concurrencyCapabilities) {
        this(machines, modType, recipeId, player, totalOperations, inferMode,
                concurrencyCapabilities, null);
    }

    public ParallelCraftGroup(List<BoundMachine> machines, ModType modType,
                              ResourceLocation recipeId, ServerPlayer player,
                              int totalOperations, boolean inferMode,
                              @Nullable BatchConcurrencyCapabilities concurrencyCapabilities,
                              @Nullable CraftStorageEndpoint storageEndpoint) {
        this(machines, modType, recipeId, player, totalOperations, inferMode,
                concurrencyCapabilities, storageEndpoint, machines.size());
    }

    public ParallelCraftGroup(List<BoundMachine> machines, ModType modType,
                              ResourceLocation recipeId, ServerPlayer player,
                              int totalOperations, boolean inferMode,
                              @Nullable BatchConcurrencyCapabilities concurrencyCapabilities,
                              @Nullable CraftStorageEndpoint storageEndpoint, int maxWorkers) {
        this.modType = modType;
        this.recipeId = recipeId;
        this.inferMode = inferMode;
        this.concurrencyCapabilities = concurrencyCapabilities;
        this.storageEndpoint = storageEndpoint;
        this.player = player;
        this.operations = new OperationQueue(totalOperations);
        this.safelyRecoverableVirtual = new boolean[totalOperations];
        java.util.Arrays.fill(this.safelyRecoverableVirtual, true);
        int workerId = 0;
        for (BoundMachine machine : machines) {
            if (!needsMoreWorkers(workers.size(), maxWorkers, totalOperations)) break;
            ChildPreparation preparation = prepareChildDelegate(machine, player);
            if (preparation.state() != ChildPreparationState.READY || preparation.delegate() == null) {
                if (preparation.state() == ChildPreparationState.FATAL) {
                    RSIntegrationMod.LOGGER.warn("[RSI-ParallelGroup] Rejecting worker {}: {}",
                            machine.pos(), preparation.detail());
                }
                continue;
            }
            workers.add(new WorkerSlot(workerId++, machine, preparation.delegate()));
            if (representativePos.equals(BlockPos.ZERO)) representativePos = machine.pos();
        }
        try {
            refreshMaterialSpecs();
        } catch (RuntimeException exception) {
            releasePreparationResources();
            throw exception;
        }
        RSIntegrationMod.LOGGER.debug("[RSI-ParallelGroup] Created {}/{} workers for {} operations of {}",
                workers.size(), machines.size(), totalOperations, recipeId);
    }

    static boolean needsMoreWorkers(int readyWorkers, int maxWorkers, int totalOperations) {
        return readyWorkers < Math.min(Math.max(0, maxWorkers), Math.max(0, totalOperations));
    }

    @Override
    public boolean validateAndInit(ServerPlayer player, ResourceLocation recipeId,
                                   @Nullable ResourceLocation dim, BlockPos pos) {
        return !workers.isEmpty();
    }

    @Nullable
    @Override
    public List<IngredientSpec> getRequiredMaterials() {
        if (baseSpecs == null || baseSpecs.isEmpty()) return null;
        List<IngredientSpec> all = new ArrayList<>(baseSpecs.size() * operations.totalOperations());
        for (int i = 0; i < operations.totalOperations(); i++) all.addAll(baseSpecs);
        return all;
    }

    @Nullable
    public List<IngredientSpec> getOperationMaterials() {
        return graphSpecs.isEmpty() ? null : List.copyOf(graphSpecs);
    }

    /** Complete per-operation material layout used by the flat executor. */
    @Nullable
    public List<IngredientSpec> getFlatOperationMaterials() {
        return baseSpecs == null || baseSpecs.isEmpty() ? null : List.copyOf(baseSpecs);
    }

    @Override
    public List<IngredientSpec> getSupplementalSpecs() {
        return repeatSpecs(supplementalSpecs, operations.totalOperations());
    }

    @Override
    public List<ItemStack> mergeSupplementalMaterials(
            List<ItemStack> graphMaterials, List<ItemStack> supplementalMaterials) {
        if (supplementalMaterials.isEmpty()) return graphMaterials;
        if (workers.isEmpty()) return List.of();
        IBatchDelegate child = workers.get(0).delegate;
        return mergeOperationSlices(graphMaterials, supplementalMaterials,
                operations.totalOperations(), graphSpecs.size(), supplementalSpecs.size(),
                child::mergeSupplementalMaterials);
    }

    static List<IngredientSpec> repeatSpecs(List<IngredientSpec> specs, int operationCount) {
        if (specs.isEmpty() || operationCount <= 0) return List.of();
        List<IngredientSpec> all = new ArrayList<>(specs.size() * operationCount);
        for (int i = 0; i < operationCount; i++) all.addAll(specs);
        return List.copyOf(all);
    }

    static List<ItemStack> mergeOperationSlices(
            List<ItemStack> graphMaterials, List<ItemStack> supplementalMaterials,
            int operationCount, int graphPerOperation, int supplementalPerOperation,
            BiFunction<List<ItemStack>, List<ItemStack>, List<ItemStack>> merger) {
        if (graphMaterials.size() != graphPerOperation * operationCount
                || supplementalMaterials.size() != supplementalPerOperation * operationCount) {
            throw new IllegalArgumentException("parallel material slices do not match operation specs");
        }
        List<ItemStack> merged = new ArrayList<>();
        for (int operation = 0; operation < operationCount; operation++) {
            int graphStart = operation * graphPerOperation;
            int supplementalStart = operation * supplementalPerOperation;
            merged.addAll(merger.apply(
                    List.copyOf(graphMaterials.subList(
                            graphStart, graphStart + graphPerOperation)),
                    List.copyOf(supplementalMaterials.subList(
                            supplementalStart, supplementalStart + supplementalPerOperation))));
        }
        return List.copyOf(merged);
    }

    public List<IBatchDelegate.MaterialReservationScope> getMaterialReservationScopes() {
        if (workers.isEmpty() || workers.get(0).delegate == null) return List.of();
        return workers.get(0).delegate.getMaterialReservationScopes();
    }

    /**
     * Preserve the child's private-ledger graph contract when this group is
     * used as the graph node delegate.  Some machines (for example Arcane
     * Iterator) deliberately expose no graph material specs because they run
     * several internal stages and own extraction themselves.  Without this
     * delegation the group is rejected as having no graph materials before it
     * can dispatch its workers through their private ledgers.
     */
    @Override
    public boolean requiresPrivateLedgerGraphDispatch() {
        return !workers.isEmpty()
                && requiresPrivateLedgerGraphDispatch(workers.get(0).delegate);
    }

    static boolean requiresPrivateLedgerGraphDispatch(@Nullable IBatchDelegate delegate) {
        return delegate != null && delegate.requiresPrivateLedgerGraphDispatch();
    }

    public void setReservationTokens(List<ExtractionLedger.ReservationToken> tokens) {
        this.reservationTokens = List.copyOf(tokens);
    }

    /**
     * Records the reservations that equip each worker with its reusable
     * materials. They are intentionally separate from operation tokens because
     * operation completion must not consume a catalyst that remains installed
     * for later operations.
     */
    public void setReusableReservationTokens(List<ExtractionLedger.ReservationToken> tokens) {
        this.reusableReservationTokens = List.copyOf(tokens);
        this.reusableReservationsSettled = new boolean[this.reusableReservationTokens.size()];
    }

    public void setVirtualDebits(List<List<ItemStack>> debits) {
        List<List<ItemStack>> copies = new ArrayList<>(debits.size());
        for (List<ItemStack> debit : debits) copies.add(List.copyOf(copyStacks(debit)));
        this.virtualDebits = List.copyOf(copies);
    }

    public void setProducerDebits(List<List<ItemStack>> debits) {
        List<List<ItemStack>> copies = new ArrayList<>(debits.size());
        for (List<ItemStack> debit : debits) copies.add(List.copyOf(copyStacks(debit)));
        this.producerDebits = List.copyOf(copies);
    }

    public void setOperationBudgets(OperationBudget craftBudget, OperationBudget globalBudget) {
        this.craftOperationBudget = craftBudget;
        this.globalOperationBudget = globalBudget;
    }

    public void setOperationKernel(OperationExecutionKernel kernel,
                                   java.util.UUID craftId, NodeId nodeId,
                                   OperationBudget craftBudget) {
        this.operationKernel = kernel;
        this.craftId = craftId;
        this.nodeId = nodeId;
        this.craftOperationBudget = craftBudget;
    }

    @Override
    public boolean tryStartWithMaterials(ServerPlayer player, List<ItemStack> materials,
                                         ExtractionLedger sharedLedger) {
        if (baseSpecs == null || baseSpecs.isEmpty()) return false;
        int perOperation = baseSpecs.size();
        if (materials.size() != perOperation * operations.totalOperations()
                || reservationTokens.size() != operations.totalOperations()
                || virtualDebits.size() != operations.totalOperations()
                || (!producerDebits.isEmpty()
                && producerDebits.size() != operations.totalOperations())) {
            return false;
        }
        List<List<ItemStack>> slices = new ArrayList<>(operations.totalOperations());
        for (int operation = 0; operation < operations.totalOperations(); operation++) {
            List<ItemStack> slice = new ArrayList<>(perOperation);
            int offset = operation * perOperation;
            for (int i = 0; i < perOperation; i++) {
                ItemStack material = materials.get(offset + i);
                slice.add(material == null || material.isEmpty() ? ItemStack.EMPTY : material.copy());
            }
            slices.add(List.copyOf(slice));
        }
        this.operationMaterials = List.copyOf(slices);
        this.sharedLedger = sharedLedger;
        this.sharedMaterialMode = true;
        this.player = player;
        return startInitialWorkers();
    }

    @Override
    public boolean tryStartSingleCraft(ServerPlayer player) {
        this.player = player;
        this.sharedMaterialMode = false;
        return startInitialWorkers();
    }

    @Override
    public boolean tryStartSingleCraft(ServerPlayer player, ExtractionLedger sharedLedger) {
        return tryStartSingleCraft(player);
    }

    private boolean startInitialWorkers() {
        if (workers.isEmpty()) {
            beginDraining("no validated workers are available");
            return false;
        }
        for (WorkerSlot worker : workers) {
            startNext(worker);
            if (operations.queuedOperations() == 0) break;
        }
        // Resource contention is transient. Accept the group and retry queued
        // operations from observeCraft() instead of failing after admission.
        started = true;
        return true;
    }

    private boolean startNext(WorkerSlot worker) {
        int operationId = operations.nextQueuedOperation();
        if (operationId < 0) return false;

        boolean reusingPreparedDelegate = worker.pristineDelegate;
        ChildPreparation preparation = reusingPreparedDelegate
                ? ChildPreparation.ready(worker.delegate)
                : prepareChildDelegate(worker.machine, player);
        if (preparation.state() == ChildPreparationState.RETRY) return false;
        if (preparation.state() == ChildPreparationState.FATAL || preparation.delegate() == null) {
            worker.pristineDelegate = false;
            beginDraining(preparation.detail().isEmpty()
                    ? "worker contract failed at " + worker.machine.pos()
                    : preparation.detail());
            return false;
        }
        IBatchDelegate delegate = preparation.delegate();
        int batchSize;
        try {
            int requestedBatch = sharedMaterialMode
                    ? Math.max(1, delegate.preferredParallelBatchSize(
                            operations.totalOperations(), workers.size()))
                    : 1;
            batchSize = compatibleBatchSize(operationId,
                    Math.min(requestedBatch, operations.queuedOperations()));
            delegate.prepareGraphBatch(batchSize);
            delegate.prepareOperationCount(operations.totalOperations());
        } catch (RuntimeException exception) {
            if (!reusingPreparedDelegate) releasePreparationQuietly(delegate, worker.machine);
            beginDraining("worker batch preparation threw at " + worker.machine.pos());
            RSIntegrationMod.LOGGER.warn(
                    "[RSI-ParallelGroup] Worker batch preparation threw at {}",
                    worker.machine.pos(), exception);
            return false;
        }
        // Acquire the operation scope before consuming the queue id. A busy
        // machine/capture/budget leaves the operation queued for a later tick.
        boolean acquired;
        try {
            acquired = acquireOperationResources(worker, delegate, operationId);
        } catch (RuntimeException exception) {
            acquired = false;
            beginDraining("worker resource preparation threw at " + worker.machine.pos());
            RSIntegrationMod.LOGGER.warn(
                    "[RSI-ParallelGroup] Worker resource preparation threw at {}",
                    worker.machine.pos(), exception);
        }
        if (!acquired) {
            if (!reusingPreparedDelegate) releasePreparationQuietly(delegate, worker.machine);
            return false;
        }
        List<Integer> claimedOperations = operations.claimBatch(worker.id, batchSize);
        if (claimedOperations.isEmpty() || claimedOperations.get(0) != operationId) {
            closeOperationResources(worker);
            if (!reusingPreparedDelegate) releasePreparationQuietly(delegate, worker.machine);
            throw new IllegalStateException("operation queue changed during resource acquisition");
        }
        worker.pristineDelegate = false;
        worker.delegate = delegate;
        worker.operationIds = claimedOperations;
        if (claimedOperations.size() > 1) {
            RSIntegrationMod.LOGGER.debug(
                    "[RSI-ParallelBatch] recipe={} worker={} batch={} queuedAfterClaim={}",
                    recipeId, worker.machine.pos(), claimedOperations.size(),
                    operations.queuedOperations());
        }
        // Once start is attempted, the delegate may already have moved this
        // operation's virtual inputs into the physical machine. Never synthesize
        // those inputs back unless the operation was never dispatched.
        for (int claimed : claimedOperations) safelyRecoverableVirtual[claimed] = false;
        worker.needsFailureCleanup = false;
        try {
            if (worker.operationSession != null && !worker.operationSession.commit(() -> true)) {
                handleFailedStart(worker, "worker commit boundary failed at " + worker.machine.pos());
                return false;
            }
            boolean accepted;
            if (sharedMaterialMode) {
                if (delegate instanceof AbstractBatchDelegate abstractDelegate) {
                    abstractDelegate.useSharedLedger(sharedLedger);
                }
                List<ItemStack> batchMaterials = aggregateOperationMaterials(claimedOperations);
                accepted = worker.operationSession != null
                        ? worker.operationSession.tryStart(() -> delegate.tryStartWithMaterials(player,
                        batchMaterials, sharedLedger))
                        : delegate.tryStartWithMaterials(player,
                        batchMaterials, sharedLedger);
            } else {
                accepted = worker.operationSession != null
                        ? worker.operationSession.tryStart(() -> delegate.tryStartSingleCraft(player))
                        : delegate.tryStartSingleCraft(player);
            }
            if (!accepted) {
                handleFailedStart(worker, "worker start failed at " + worker.machine.pos());
                return false;
            }
            // Deferred-output machines only learn the concrete item while
            // starting. Their operation session was acquired before that, so
            // attach a companion interceptor now to claim delayed world drops.
            armCaptureAfterStart(worker, delegate);
            worker.hasStartedOperation = true;
            return true;
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-ParallelGroup] Worker start threw at {}",
                    worker.machine.pos(), e);
            handleFailedStart(worker, "worker start threw at " + worker.machine.pos());
            return false;
        }
    }

    private int compatibleBatchSize(int firstOperation, int requested) {
        if (!sharedMaterialMode || requested <= 1) return 1;
        int compatible = 1;
        for (int candidate = 2; candidate <= requested; candidate++) {
            List<Integer> ids = java.util.stream.IntStream
                    .range(firstOperation, firstOperation + candidate).boxed().toList();
            if (canAggregateOperationMaterials(ids)) compatible = candidate;
            else break;
        }
        return compatible;
    }

    private boolean canAggregateOperationMaterials(List<Integer> operationIds) {
        if (operationIds.isEmpty() || baseSpecs == null || baseSpecs.isEmpty()) return false;
        int perOperation = baseSpecs.size();
        for (int materialIndex = 0; materialIndex < perOperation; materialIndex++) {
            ItemStack first = ItemStack.EMPTY;
            for (int operationId : operationIds) {
                ItemStack stack = operationMaterials.get(operationId).get(materialIndex);
                if (stack == null || stack.isEmpty()) continue;
                if (first.isEmpty()) first = stack;
                else if (!MaterialMatcher.equivalentRuntimeFragment(first, stack)) return false;
            }
        }
        return true;
    }

    private List<ItemStack> aggregateOperationMaterials(List<Integer> operationIds) {
        if (operationIds.size() == 1) {
            return copyStacksKeepingEmpty(operationMaterials.get(operationIds.get(0)));
        }
        int perOperation = baseSpecs.size();
        List<ItemStack> aggregated = new ArrayList<>(perOperation);
        for (int materialIndex = 0; materialIndex < perOperation; materialIndex++) {
            ItemStack combined = ItemStack.EMPTY;
            for (int operationId : operationIds) {
                ItemStack stack = operationMaterials.get(operationId).get(materialIndex);
                if (stack == null || stack.isEmpty()) continue;
                if (combined.isEmpty()) combined = stack.copy();
                else combined.grow(stack.getCount());
            }
            aggregated.add(combined);
        }
        return List.copyOf(aggregated);
    }

    @Override
    public boolean isCraftComplete(ServerLevel level) {
        return observeCraft(level).phase() == CraftPhase.DONE;
    }

    @Override
    public CraftObservation observeCraft(ServerLevel level) {
        if (!started && !draining) return new CraftObservation(CraftPhase.WAITING_FOR_START);

        for (WorkerSlot worker : workers) {
            if (!worker.running()) continue;
            CraftObservation observation;
            try {
                observation = worker.delegate.observeCraft(level);
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.warn("[RSI-ParallelGroup] Worker observation threw at {}",
                        worker.machine.pos(), e);
                // This worker can no longer be observed to natural completion. Its
                // reservation stays unsettled and onBatchFailed recovers its machine.
                operations.abandonBatch(worker.id);
                worker.clearOperations();
                closeOperationResources(worker);
                worker.needsFailureCleanup = true;
                beginDraining("worker observation failed at " + worker.machine.pos());
                continue;
            }
            worker.lastObservation = observation;
            if (observation.phase() == CraftPhase.FAILED) {
                operations.abandonBatch(worker.id);
                worker.clearOperations();
                closeOperationResources(worker);
                worker.needsFailureCleanup = true;
                beginDraining(worker.machine.pos() + ": " + observation.detail());
                continue;
            }
            boolean capturedWorldOutput = hasCapturedExpectedOutput(worker);
            if (observation.phase() == CraftPhase.DONE || capturedWorldOutput) {
                settleCompletedOperation(worker);
            }
        }

        if (!draining && operations.queuedOperations() > 0) {
            dispatchQueuedOperations();
        }
        if (draining && operations.isDrained()) {
            return new CraftObservation(CraftPhase.FAILED, failureDetail);
        }
        if (operations.isComplete()) return new CraftObservation(CraftPhase.DONE);
        reportWaitingWorkers(level.getGameTime());
        return new CraftObservation(CraftPhase.WORKING);
    }

    private void reportWaitingWorkers(long tick) {
        if (tick < nextWaitCheckTick) return;
        nextWaitCheckTick = tick + 20;
        long captured = 0;
        for (WorkerSlot worker : workers) {
            if (worker.running()) captured += capturedSnapshot(worker).stream()
                    .mapToLong(ItemStack::getCount).sum();
        }
        int completed = operations.completedOperations();
        if (lastProgressTick < 0 || completed != lastObservedCompleted || captured != lastObservedCaptured) {
            lastProgressTick = tick;
            lastObservedCompleted = completed;
            lastObservedCaptured = captured;
        }
        if (!shouldReportWait(tick, lastProgressTick, lastWaitReportTick)) return;
        lastWaitReportTick = tick;
        RSIntegrationMod.LOGGER.warn(
                "[RSI-ParallelWait] craft={} node={} recipe={} completed={}/{} running={} queued={} draining={}",
                craftId, nodeId, recipeId, completed, operations.totalOperations(),
                operations.runningOperations(), operations.queuedOperations(), draining);
        for (WorkerSlot worker : workers) {
            if (!worker.running()) continue;
            ItemStack expected = worker.delegate.getExpectedOutput();
            if (expected == null || expected.isEmpty()) {
                ExpectedProduction production = worker.delegate.getExpectedProduction();
                if (production != null) expected = production.item().copyWithCount(production.count());
            }
            String machineState;
            try {
                machineState = worker.delegate.describeExecutionState();
            } catch (RuntimeException exception) {
                machineState = "machine_state=unavailable error=" + exception.getClass().getSimpleName();
            }
            RSIntegrationMod.LOGGER.warn(
                    "[RSI-ParallelWait] craft={} worker={} dimension={} machine={} operations={} phase={} "
                            + "expected={} captured={} detail={} {}",
                    craftId, worker.id, worker.machine.dim(),
                    worker.delegate.getOperationMachinePos(worker.machine.pos()), worker.operationIds,
                    worker.lastObservation.phase(), expected, capturedSnapshot(worker),
                    worker.lastObservation.detail(), machineState);
        }
    }

    static boolean shouldReportWait(long tick, long lastProgressTick, long lastReportTick) {
        return lastProgressTick >= 0 && tick - lastProgressTick >= 100
                && (lastReportTick < 0 || tick - lastReportTick >= 600);
    }

    private void dispatchQueuedOperations() {
        for (WorkerSlot worker : workers) {
            if (operations.queuedOperations() == 0) break;
            if (!worker.running()) startNext(worker);
        }
    }

    private boolean settleCompletedOperation(WorkerSlot worker) {
        List<Integer> operationIds = worker.operationIds;
        List<ItemStack> actual = new ArrayList<>(drainCapture(worker));
        try {
            actual.addAll(worker.delegate.collectAllResults(player));
        } catch (Exception e) {
            operations.abandonBatch(worker.id);
            worker.clearOperations();
            closeOperationResources(worker);
            worker.needsFailureCleanup = true;
            beginDraining("worker result collection failed at " + worker.machine.pos());
            return false;
        }
        actual.removeIf(stack -> stack == null || stack.isEmpty());

        ExpectedProduction expected = worker.delegate.getExpectedProduction();
        if (expected != null && countMatching(actual, expected) < expected.count()) {
            // DONE proves the inputs were consumed. Preserve the residual output and
            // settle this token so an externally extracted result cannot pair with a refund.
            settledResults.addAll(copyStacks(actual));
            settleReservations(operationIds);
            operations.abandonBatch(worker.id);
            worker.clearOperations();
            closeOperationResources(worker);
            worker.needsFailureCleanup = true;
            beginDraining("worker output was externally extracted at " + worker.machine.pos());
            return false;
        }
        ItemStack expectedWorld = worker.delegate.getExpectedOutput();
        if (expected == null && expectedWorld != null && !expectedWorld.isEmpty() && actual.isEmpty()) {
            settleReservations(operationIds);
            operations.abandonBatch(worker.id);
            worker.clearOperations();
            closeOperationResources(worker);
            worker.needsFailureCleanup = true;
            beginDraining("expected world output was not captured at " + worker.machine.pos());
            return false;
        }

        // Output ownership is already proven at this point. Commit the operation
        // before cleanup so a cleanup exception cannot pair real output with an input refund.
        settleReservations(operationIds);
        settledResults.addAll(copyStacks(actual));
        addSecondaryOutputs(worker.delegate);
        try {
            worker.delegate.onBatchFinished(player);
        } catch (Exception e) {
            operations.abandonBatch(worker.id);
            worker.clearOperations();
            closeOperationResources(worker);
            worker.needsFailureCleanup = true;
            beginDraining("worker finish cleanup failed at " + worker.machine.pos());
            return false;
        }
        operations.completeBatch(worker.id);
        worker.clearOperations();
        closeOperationResources(worker);

        if (!draining && operations.queuedOperations() > 0) startNext(worker);
        return !draining;
    }

    private void addSecondaryOutputs(IBatchDelegate delegate) {
        if (delegate.collectsPhysicalSecondaryOutputs() || machineServer == null) return;
        ServerLevel overworld = machineServer.overworld();
        if (overworld == null) return;
        Recipe<?> recipe = overworld.getRecipeManager().byKey(recipeId).orElse(null);
        if (recipe == null) return;
        for (ItemStack secondary : ModRecipeHandlers.tryGetSecondaryOutputs(
                recipe, overworld.registryAccess())) {
            if (secondary != null && !secondary.isEmpty()) settledResults.add(secondary.copy());
        }
    }

    /** Drain newly settled outputs without waiting for the entire group. */
    public List<ItemStack> drainSettledResults() {
        if (settledResults.isEmpty()) return List.of();
        List<ItemStack> drained = copyStacks(settledResults);
        settledResults.clear();
        return List.copyOf(drained);
    }

    /** Virtual inputs for operations that were never dispatched to a machine. */
    public List<ItemStack> drainQueuedMaterialsForRecovery() {
        if (!draining || !sharedMaterialMode || queuedMaterialsRecovered) return List.of();
        queuedMaterialsRecovered = true;
        List<ItemStack> recovery = new ArrayList<>();
        for (int operation = 0; operation < virtualDebits.size(); operation++) {
            if (safelyRecoverableVirtual[operation]) {
                recovery.addAll(copyStacks(virtualDebits.get(operation)));
            }
        }
        return List.copyOf(recovery);
    }

    public List<ItemStack> drainQueuedProducerMaterialsForRecovery() {
        if (!draining || !sharedMaterialMode || producerDebits.isEmpty()) return List.of();
        List<ItemStack> recovery = new ArrayList<>();
        for (int operation = 0; operation < producerDebits.size(); operation++) {
            if (safelyRecoverableVirtual[operation]) {
                recovery.addAll(copyStacks(producerDebits.get(operation)));
            }
        }
        producerDebits = List.of();
        return List.copyOf(recovery);
    }

    public List<ExtractionLedger.ReservationToken> queuedReservationTokensForRecovery() {
        if (!draining || !sharedMaterialMode) return List.of();
        List<ExtractionLedger.ReservationToken> recovery = new ArrayList<>();
        for (int operation = 0; operation < reservationTokens.size(); operation++) {
            if (safelyRecoverableVirtual[operation]) recovery.add(reservationTokens.get(operation));
        }
        return List.copyOf(recovery);
    }

    public boolean isDraining() {
        return draining;
    }

    /** Stop queued operations while allowing already-running workers to drain. */
    public void stopDispatch() {
        beginDraining("dispatch stopped by graph scheduler");
    }

    public int getCompletedOperations() {
        return operations.completedOperations();
    }

    public int getTotalOperations() {
        return operations.totalOperations();
    }

    public int getRunningOperations() {
        return operations.runningOperations();
    }

    @Override
    public ItemStack collectResult(ServerPlayer player) {
        List<ItemStack> results = collectAllResults(player);
        return results.isEmpty() ? ItemStack.EMPTY : results.get(0);
    }

    @Override
    public List<ItemStack> collectAllResults(ServerPlayer player) {
        return drainSettledResults();
    }

    @Nullable
    @Override
    public ExpectedProduction getExpectedProduction() {
        return null;
    }

    @Nullable
    @Override
    public ItemStack getExpectedOutput() {
        return null;
    }

    @Override
    public void onBatchFailed(ServerPlayer player, String reason) {
        int runningBeforeCleanup = getRunningOperations();
        int queuedBeforeCleanup = operations.queuedOperations();
        failureTerminalClass = runningBeforeCleanup > 0
                ? OperationExecutionKernel.TerminalClass.IN_FLIGHT
                : queuedBeforeCleanup > 0
                ? OperationExecutionKernel.TerminalClass.PRE_START
                : null;
        physicalFailureCleanupCompleted = true;
        List<ItemStack> recoveredInputs = new ArrayList<>();
        boolean recoveryAudited = true;
        beginDraining(reason);
        for (WorkerSlot worker : workers) {
            // A physical batch may have converted only part of its input before
            // cancellation. Settle exactly the operations proven by captured
            // outputs; the child cleanup removes the still-unconverted entities
            // and the ledger refunds only the remaining reservation tokens.
            preserveCapturedProgress(worker, drainCapture(worker));
            closeOperationResources(worker);
            if (worker.delegate != null) {
                boolean physicalCleanupRequired = worker.running() || worker.needsFailureCleanup;
                try {
                    cleanupPreparedDelegate(worker.delegate, physicalCleanupRequired, player, reason);
                    if (physicalCleanupRequired && !physicalCleanupCompleted(worker.delegate)) {
                        physicalFailureCleanupCompleted = false;
                    }
                    if (physicalCleanupRequired) {
                        List<ItemStack> recovered = worker.delegate.failureRecoveredInputs();
                        if (recovered == null) recoveryAudited = false;
                        else recoveredInputs.addAll(recovered);
                    }
                } catch (Exception e) {
                    if (physicalCleanupRequired) physicalFailureCleanupCompleted = false;
                    if (physicalCleanupRequired) recoveryAudited = false;
                    RSIntegrationMod.LOGGER.debug("[RSI-ParallelGroup] Worker cleanup failed at {}",
                            worker.machine.pos(), e);
                }
            }
            worker.clearOperations();
            worker.needsFailureCleanup = false;
        }
        failureRecoveredInputs = recoveryAudited ? List.copyOf(recoveredInputs) : null;
        started = false;
        RSIntegrationMod.LOGGER.debug(
                "[RSI-ParallelGroup] failure cleanup recipe={} terminal={} physicalCleanupCompleted={} running={} queued={}",
                recipeId, failureTerminalClass, physicalFailureCleanupCompleted,
                runningBeforeCleanup, queuedBeforeCleanup);
    }

    /** True when all physical worker delegates completed their failure cleanup. */
    public boolean physicalFailureCleanupCompleted() {
        return physicalFailureCleanupCompleted;
    }

    @Nullable
    @Override
    public List<ItemStack> failureRecoveredInputs() {
        return failureRecoveredInputs;
    }

    /** Terminal state captured before {@link #onBatchFailed} clears worker state. */
    @Nullable
    public OperationExecutionKernel.TerminalClass failureTerminalClass() {
        return failureTerminalClass;
    }

    private static boolean physicalCleanupCompleted(IBatchDelegate delegate) {
        if (delegate instanceof AbstractBatchDelegate abstractDelegate) {
            return abstractDelegate.physicalFailureCleanupCompleted();
        }
        if (delegate instanceof ParallelCraftGroup group) {
            return group.physicalFailureCleanupCompleted();
        }
        // Non-Abstract delegates own no shared physical cleanup status. Their
        // callback completed normally, so treat it as successful here.
        return true;
    }

    static void cleanupPreparedDelegate(IBatchDelegate delegate,
                                        boolean physicalCleanupRequired,
                                        @Nullable ServerPlayer player,
                                        String reason) {
        if (physicalCleanupRequired) delegate.onBatchFailed(player, reason);
        else delegate.releasePreparationResources();
    }

    private void preserveCapturedProgress(WorkerSlot worker, List<ItemStack> captured) {
        if (captured.isEmpty() || worker.operationIds.isEmpty()) return;
        ItemStack expectedBatch = worker.delegate.getExpectedOutput();
        int completed = completedExecutionsFromCapture(
                expectedBatch, worker.operationIds.size(), captured);
        if (completed > 0) {
            settleReservations(worker.operationIds.subList(0, completed));
            settledResults.addAll(copyStacks(captured));
        }
    }

    static int completedExecutionsFromCapture(ItemStack expectedBatch, int batchSize,
                                              List<ItemStack> captured) {
        if (batchSize <= 0 || expectedBatch == null || expectedBatch.isEmpty()
                || captured == null || captured.isEmpty()) return 0;
        int matching = captured.stream()
                .filter(stack -> stack != null && !stack.isEmpty()
                        && MaterialMatcher.sameRuntimeFragment(expectedBatch, stack))
                .mapToInt(ItemStack::getCount)
                .sum();
        if (matching <= 0) return 0;
        int perExecution = Math.max(1, expectedBatch.getCount() / batchSize);
        return Math.min(batchSize, Math.max(1, matching / perExecution));
    }

    @Override
    public void onBatchFinished(@NotNull ServerPlayer player) {
        started = false;
        for (WorkerSlot worker : workers) {
            drainCapture(worker);
            closeOperationResources(worker);
            if (worker.delegate != null) {
                releasePreparationQuietly(worker.delegate, worker.machine);
            }
        }
    }

    @Override
    public void releasePreparationResources() {
        for (WorkerSlot worker : workers) {
            closeOperationResources(worker);
            if (worker.delegate != null) {
                releasePreparationQuietly(worker.delegate, worker.machine);
            }
        }
    }

    @Override
    public void releaseReusableMaterials(@NotNull ServerPlayer player) {
        for (int workerIndex = 0; workerIndex < workers.size(); workerIndex++) {
            WorkerSlot worker = workers.get(workerIndex);
            if (worker.delegate == null) continue;
            worker.delegate.releaseReusableMaterials(player);

            // A started worker has physically received its reusable material.
            // Its delegate has just returned that material to RS, so remove only
            // the corresponding reservation from the shared ledger. Queued
            // workers never owned their physical material and remain refundable.
            if (sharedMaterialMode && sharedLedger != null && worker.hasStartedOperation
                    && workerIndex < reusableReservationTokens.size()
                    && !reusableReservationsSettled[workerIndex]) {
                sharedLedger.settleCommitted(reusableReservationTokens.get(workerIndex));
                reusableReservationsSettled[workerIndex] = true;
            }
        }
    }

    @Override
    public BlockPos getMachinePos() {
        return representativePos;
    }

    public int getChildCount() {
        return workers.size();
    }

    /** Compact stable summary for progress snapshots; avoids sending every worker position. */
    public String machineLabel() {
        if (workers.isEmpty()) return "";
        BoundMachine first = workers.get(0).machine;
        String label = first.dim() + "@" + first.pos().toShortString();
        return workers.size() > 1 ? label + " (+" + (workers.size() - 1) + ")" : label;
    }

    public void setMachineServer(MinecraftServer server) {
        this.machineServer = server;
        for (WorkerSlot worker : workers) configureDelegate(worker.delegate, worker.machine);
    }

    public void setTargetOutput(@Nullable ItemStack targetOutput) {
        this.targetOutput = targetOutput == null || targetOutput.isEmpty() ? null : targetOutput.copy();
        for (WorkerSlot worker : workers) configureDelegate(worker.delegate, worker.machine);
        refreshMaterialSpecs();
    }

    private void refreshMaterialSpecs() {
        if (workers.isEmpty()) {
            baseSpecs = null;
            graphSpecs = List.of();
            supplementalSpecs = List.of();
            return;
        }
        IBatchDelegate child = workers.get(0).delegate;
        List<IngredientSpec> required = child.getRequiredMaterials();
        baseSpecs = required == null ? null : List.copyOf(required);
        graphSpecs = List.copyOf(child.getGraphSpecs());
        supplementalSpecs = List.copyOf(child.getSupplementalSpecs());
    }

    private ChildPreparation prepareChildDelegate(BoundMachine machine, ServerPlayer player) {
        ModType executionType = machine.type() == null ? modType : machine.type();
        IBatchDelegate delegate = createChildDelegate(executionType);
        if (delegate == null) {
            return ChildPreparation.fatal("delegate factory returned null for " + executionType.id());
        }
        try {
            configureDelegate(delegate, machine);
            IBatchDelegate.PreparationResult result = PreparationMessageScope.prepare(
                    delegate, player, recipeId, machine.dim(), machine.pos());
            if (result.state() == IBatchDelegate.PreparationState.RETRY) {
                releasePreparationQuietly(delegate, machine);
                return ChildPreparation.retry(result.detail());
            }
            if (result.state() == IBatchDelegate.PreparationState.FATAL) {
                releasePreparationQuietly(delegate, machine);
                return ChildPreparation.fatal(result.detail());
            }
            configureDelegate(delegate, machine);
            return ChildPreparation.ready(delegate);
        } catch (Exception e) {
            releasePreparationQuietly(delegate, machine);
            RSIntegrationMod.LOGGER.debug("[RSI-ParallelGroup] Worker preparation failed at {}",
                    machine.pos(), e);
            return ChildPreparation.retry("worker preparation temporarily failed at " + machine.pos());
        }
    }

    private static void releasePreparationQuietly(IBatchDelegate delegate, BoundMachine machine) {
        try {
            delegate.releasePreparationResources();
        } catch (RuntimeException exception) {
            RSIntegrationMod.LOGGER.debug(
                    "[RSI-ParallelGroup] Preparation cleanup failed at {}", machine.pos(), exception);
        }
    }

    private void configureDelegate(IBatchDelegate delegate, BoundMachine machine) {
        if (!(delegate instanceof AbstractBatchDelegate abstractDelegate)) return;
        abstractDelegate.setStorageEndpoint(storageEndpoint);
        abstractDelegate.setMachineDim(machine.dim());
        if (machineServer != null) abstractDelegate.setMachineServer(machineServer);
        if (targetOutput != null) abstractDelegate.setTargetOutput(targetOutput);
    }

    private boolean acquireOperationResources(WorkerSlot worker, IBatchDelegate delegate,
                                              int operationId) {
        if (operationKernel == null || craftId == null || nodeId == null
                || craftOperationBudget == null) {
            if (worker.hasStartedOperation && craftOperationBudget != null
                    && globalOperationBudget != null) {
                return OperationBudget.tryRecordStart(craftOperationBudget, globalOperationBudget);
            }
            armCaptureLegacy(worker, delegate);
            return true;
        }
        GraphConcurrencyPolicy.Decision concurrency = GraphConcurrencyPolicy.decide(
                modType.id(), delegate, concurrencyCapabilities);
        if (!operationGroupAcceptsChild(concurrency.exclusive(), workers.size())) {
            RSIntegrationMod.LOGGER.debug(
                    "[RSI-ParallelGroup] Rejecting capability-exclusive child delegate={} "
                            + "for {} workers reason={}",
                    delegate.getClass().getSimpleName(), workers.size(), concurrency.reason());
            return false;
        }
        ItemStack expected = delegate.getExpectedOutput();
        var region = delegate.getOutputCaptureRegion();
        boolean ownsWorldCapture = !concurrency.exclusive()
                && concurrency.capabilities().outputOwnership()
                == BatchConcurrencyCapabilities.OutputOwnership.OWNED_WORLD_CAPTURE;
        if (!concurrency.exclusive() && expected != null && !expected.isEmpty()
                && !ownsWorldCapture) return false;
        OperationResourceCoordinator.CaptureRequest capture = expected != null
                && !expected.isEmpty() && region != null
                 ? new OperationResourceCoordinator.CaptureRequest(worker.machine.dim(), region, expected,
                         "malum".equals(modType.id())
                                 || delegate.allowsOverlappingOutputCaptureOrigins())
                 : null;
        List<MachineLeaseRegistry.MachineKey> machineScope = new ArrayList<>();
        BlockPos operationMachinePos = delegate.getOperationMachinePos(worker.machine.pos());
        machineScope.add(new MachineLeaseRegistry.MachineKey(
                worker.machine.dim(), operationMachinePos, modType.id()));
        if (concurrency.capabilities() != null) {
            for (BlockPos offset : concurrency.capabilities().supportOffsets()) {
                machineScope.add(new MachineLeaseRegistry.MachineKey(
                        worker.machine.dim(), operationMachinePos.offset(offset), modType.id() + ":support"));
            }
        }
        try {
            worker.operationSession = operationKernel.tryPrepare(
                    craftId, nodeId, operationId, craftOperationBudget, machineScope, capture);
            return worker.operationSession != null;
        } catch (RuntimeException exception) {
            worker.operationSession = null;
            return false;
        }
    }

    static boolean operationGroupAcceptsChild(boolean exclusive, int workerCount) {
        return !exclusive || workerCount == 1;
    }

    private void armCaptureLegacy(WorkerSlot worker, IBatchDelegate delegate) {
        ItemStack expected = delegate.getExpectedOutput();
        if (expected == null || expected.isEmpty()) return;
        var region = delegate.getOutputCaptureRegion();
        if (region == null) return;
        ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, worker.machine.dim());
        CraftOutputInterceptor.CaptureHandle handle = CraftOutputInterceptor.arm(
                dimension, region, expected,
                delegate.allowsOverlappingOutputCaptureOrigins());
        if (handle == null) return;
        worker.operationSession = null;
        legacyCaptureHandles.put(worker.id, handle);
    }

    private void armCaptureAfterStart(WorkerSlot worker, IBatchDelegate delegate) {
        if (legacyCaptureHandles.containsKey(worker.id)) return;
        ItemStack expected = delegate.getExpectedOutput();
        if (expected == null || expected.isEmpty()) return;
        var region = delegate.getOutputCaptureRegion();
        if (region == null) return;
        ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, worker.machine.dim());
        CraftOutputInterceptor.CaptureHandle handle = CraftOutputInterceptor.arm(
                dimension, region, expected,
                delegate.allowsOverlappingOutputCaptureOrigins());
        if (handle != null) legacyCaptureHandles.put(worker.id, handle);
    }

    private boolean hasCapturedExpectedOutput(WorkerSlot worker) {
        ItemStack expected = worker.delegate.getExpectedOutput();
        if (expected == null || expected.isEmpty()) return false;
        return containsExpectedWorldOutput(capturedSnapshot(worker), expected);
    }

    private List<ItemStack> capturedSnapshot(WorkerSlot worker) {
        List<ItemStack> captured = new ArrayList<>();
        if (worker.operationSession != null) captured.addAll(worker.operationSession.capturedSnapshot());
        CraftOutputInterceptor.CaptureHandle handle = legacyCaptureHandles.get(worker.id);
        if (handle != null) captured.addAll(handle.snapshot());
        return captured;
    }

    static boolean containsExpectedWorldOutput(List<ItemStack> captured, ItemStack expected) {
        if (expected == null || expected.isEmpty()) return false;
        int count = captured.stream()
                .filter(stack -> stack != null && !stack.isEmpty()
                        && MaterialMatcher.sameRuntimeFragment(expected, stack))
                .mapToInt(ItemStack::getCount)
                .sum();
        return count >= expected.getCount();
    }

    private List<ItemStack> drainCapture(WorkerSlot worker) {
        List<ItemStack> captured = new ArrayList<>();
        if (worker.operationSession != null) captured.addAll(worker.operationSession.drainCapture());
        CraftOutputInterceptor.CaptureHandle handle = legacyCaptureHandles.remove(worker.id);
        if (handle != null) captured.addAll(handle.drainAndClose());
        return List.copyOf(captured);
    }

    private void closeOperationResources(WorkerSlot worker) {
        OperationExecutionKernel.Session session = worker.operationSession;
        worker.operationSession = null;
        if (session != null) session.close();
        CraftOutputInterceptor.CaptureHandle handle = legacyCaptureHandles.remove(worker.id);
        if (handle != null) handle.drainAndClose();
    }

    private void settleReservation(int operationId) {
        if (sharedMaterialMode) {
            sharedLedger.settleCommitted(reservationTokens.get(operationId));
        }
    }

    private void settleReservations(List<Integer> operationIds) {
        for (int operationId : operationIds) settleReservation(operationId);
    }

    private void handleFailedStart(WorkerSlot worker, String detail) {
        List<ItemStack> captured = drainCapture(worker);
        preserveCapturedProgress(worker, captured);
        // A delegate returning false has rejected this batch. Its cleanup path
        // removes any materials it inserted, so the uncompleted operation
        // inputs must become eligible for virtual recovery. Keep operations
        // already proven by captured output settled to avoid duplicating them.
        int completed = completedExecutionsFromCapture(
                worker.delegate.getExpectedOutput(), worker.operationIds.size(), captured);
        for (int index = completed; index < worker.operationIds.size(); index++) {
            safelyRecoverableVirtual[worker.operationIds.get(index)] = true;
        }
        abandonUnstarted(worker);
        beginDraining(detail);
    }

    private void abandonUnstarted(WorkerSlot worker) {
        if (!worker.running()) return;
        operations.abandonBatch(worker.id);
        worker.clearOperations();
        worker.needsFailureCleanup = true;
    }

    private void beginDraining(String detail) {
        if (!draining) failureDetail = detail;
        draining = true;
        operations.stopDispatch();
    }

    private static int countMatching(List<ItemStack> stacks, ExpectedProduction expected) {
        int count = 0;
        for (ItemStack stack : stacks) {
            if (ItemStack.isSameItem(expected.item(), stack)) count += stack.getCount();
        }
        return count;
    }

    private static List<ItemStack> copyStacksKeepingEmpty(List<ItemStack> stacks) {
        List<ItemStack> copies = new ArrayList<>(stacks.size());
        for (ItemStack stack : stacks) {
            copies.add(stack == null || stack.isEmpty() ? ItemStack.EMPTY : stack.copy());
        }
        return copies;
    }

    private static List<ItemStack> copyStacks(List<ItemStack> stacks) {
        List<ItemStack> copies = new ArrayList<>(stacks.size());
        for (ItemStack stack : stacks) {
            if (stack != null && !stack.isEmpty()) copies.add(stack.copy());
        }
        return copies;
    }

    private IBatchDelegate createChildDelegate(ModType type) {
        if (type == ModType.GENERIC) return null;
        if (inferMode) return type.createInferDelegate();
        Class<? extends IBatchDelegate> versioned = ModVersionDelegateRegistry.resolve(type);
        if (versioned != null) {
            try {
                return versioned.getDeclaredConstructor().newInstance();
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.error("[RSI-ParallelGroup] Versioned delegate instantiation failed: {}",
                        versioned.getName(), e);
            }
        }
        return type.createDelegate();
    }
}
