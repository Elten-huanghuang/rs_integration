package com.huanghuang.rsintegration.crafting.loadbalancer;

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

/**
 * A dynamic pool of physical machines executing one recipe operation at a time.
 * A worker that finishes immediately collects its output and claims the next
 * operation without waiting for slower siblings.
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
    private List<IngredientSpec> baseSpecs;
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
        int operationId = -1;
        boolean pristineDelegate = true;
        boolean hasStartedOperation;
        boolean needsFailureCleanup;

        WorkerSlot(int id, BoundMachine machine, IBatchDelegate delegate) {
            this.id = id;
            this.machine = machine;
            this.delegate = delegate;
        }

        boolean running() {
            return operationId >= 0;
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
        this.modType = modType;
        this.recipeId = recipeId;
        this.inferMode = inferMode;
        this.concurrencyCapabilities = concurrencyCapabilities;
        this.player = player;
        this.operations = new OperationQueue(totalOperations);
        this.safelyRecoverableVirtual = new boolean[totalOperations];
        java.util.Arrays.fill(this.safelyRecoverableVirtual, true);
        int workerId = 0;
        for (BoundMachine machine : machines) {
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
        if (!workers.isEmpty()) baseSpecs = workers.get(0).delegate.getRequiredMaterials();
        RSIntegrationMod.debug("[RSI-ParallelGroup] Created {}/{} workers for {} operations of {}",
                workers.size(), machines.size(), totalOperations, recipeId);
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
        return baseSpecs == null ? null : List.copyOf(baseSpecs);
    }

    public List<IBatchDelegate.MaterialReservationScope> getMaterialReservationScopes() {
        if (workers.isEmpty() || workers.get(0).delegate == null) return List.of();
        return workers.get(0).delegate.getMaterialReservationScopes();
    }

    public void setReservationTokens(List<ExtractionLedger.ReservationToken> tokens) {
        this.reservationTokens = List.copyOf(tokens);
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

        ChildPreparation preparation = worker.pristineDelegate
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
        // Acquire the operation scope before consuming the queue id. A busy
        // machine/capture/budget leaves the operation queued for a later tick.
        if (!acquireOperationResources(worker, delegate, operationId)) return false;
        int claimedOperation = operations.claim(worker.id);
        if (claimedOperation != operationId) {
            closeOperationResources(worker);
            throw new IllegalStateException("operation queue changed during resource acquisition");
        }
        worker.pristineDelegate = false;
        worker.delegate = delegate;
        worker.operationId = operationId;
        // Once start is attempted, the delegate may already have moved this
        // operation's virtual inputs into the physical machine. Never synthesize
        // those inputs back unless the operation was never dispatched.
        safelyRecoverableVirtual[operationId] = false;
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
                accepted = worker.operationSession != null
                        ? worker.operationSession.tryStart(() -> delegate.tryStartWithMaterials(player,
                        copyStacksKeepingEmpty(operationMaterials.get(operationId)), sharedLedger))
                        : delegate.tryStartWithMaterials(player,
                        copyStacksKeepingEmpty(operationMaterials.get(operationId)), sharedLedger);
            } else {
                accepted = worker.operationSession != null
                        ? worker.operationSession.tryStart(() -> delegate.tryStartSingleCraft(player))
                        : delegate.tryStartSingleCraft(player);
            }
            if (!accepted) {
                handleFailedStart(worker, "worker start failed at " + worker.machine.pos());
                return false;
            }
            worker.hasStartedOperation = true;
            return true;
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-ParallelGroup] Worker start threw at {}",
                    worker.machine.pos(), e);
            handleFailedStart(worker, "worker start threw at " + worker.machine.pos());
            return false;
        }
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
                operations.abandon(worker.id);
                worker.operationId = -1;
                closeOperationResources(worker);
                worker.needsFailureCleanup = true;
                beginDraining("worker observation failed at " + worker.machine.pos());
                continue;
            }
            if (observation.phase() == CraftPhase.FAILED) {
                operations.abandon(worker.id);
                worker.operationId = -1;
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
        return new CraftObservation(CraftPhase.WORKING);
    }

    private void dispatchQueuedOperations() {
        for (WorkerSlot worker : workers) {
            if (operations.queuedOperations() == 0) break;
            if (!worker.running()) startNext(worker);
        }
    }

    private boolean settleCompletedOperation(WorkerSlot worker) {
        int operationId = worker.operationId;
        List<ItemStack> actual = new ArrayList<>(drainCapture(worker));
        try {
            actual.addAll(worker.delegate.collectAllResults(player));
        } catch (Exception e) {
            operations.abandon(worker.id);
            worker.operationId = -1;
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
            settleReservation(operationId);
            operations.abandon(worker.id);
            worker.operationId = -1;
            closeOperationResources(worker);
            worker.needsFailureCleanup = true;
            beginDraining("worker output was externally extracted at " + worker.machine.pos());
            return false;
        }
        ItemStack expectedWorld = worker.delegate.getExpectedOutput();
        if (expected == null && expectedWorld != null && !expectedWorld.isEmpty() && actual.isEmpty()) {
            settleReservation(operationId);
            operations.abandon(worker.id);
            worker.operationId = -1;
            closeOperationResources(worker);
            worker.needsFailureCleanup = true;
            beginDraining("expected world output was not captured at " + worker.machine.pos());
            return false;
        }

        // Output ownership is already proven at this point. Commit the operation
        // before cleanup so a cleanup exception cannot pair real output with an input refund.
        settleReservation(operationId);
        settledResults.addAll(copyStacks(actual));
        addSecondaryOutputs(worker.delegate);
        try {
            worker.delegate.onBatchFinished(player);
        } catch (Exception e) {
            operations.abandon(worker.id);
            worker.operationId = -1;
            closeOperationResources(worker);
            worker.needsFailureCleanup = true;
            beginDraining("worker finish cleanup failed at " + worker.machine.pos());
            return false;
        }
        operations.complete(worker.id);
        worker.operationId = -1;
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
        beginDraining(reason);
        for (WorkerSlot worker : workers) {
            // Unconfirmed capture belongs to an unsettled operation. Cleanup may
            // return its inputs, so exporting that capture here could duplicate it.
            drainCapture(worker);
            closeOperationResources(worker);
            if (worker.delegate != null && (worker.running() || worker.needsFailureCleanup)) {
                try {
                    worker.delegate.onBatchFailed(player, reason);
                } catch (Exception e) {
                    RSIntegrationMod.LOGGER.debug("[RSI-ParallelGroup] Worker cleanup failed at {}",
                            worker.machine.pos(), e);
                }
            }
            worker.operationId = -1;
            worker.needsFailureCleanup = false;
        }
        started = false;
    }

    @Override
    public void onBatchFinished(@NotNull ServerPlayer player) {
        started = false;
        for (WorkerSlot worker : workers) {
            drainCapture(worker);
            closeOperationResources(worker);
        }
    }

    @Override
    public void releaseReusableMaterials(@NotNull ServerPlayer player) {
        for (WorkerSlot worker : workers) {
            if (worker.delegate != null) worker.delegate.releaseReusableMaterials(player);
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
    }

    private ChildPreparation prepareChildDelegate(BoundMachine machine, ServerPlayer player) {
        IBatchDelegate delegate = createChildDelegate(modType);
        if (delegate == null) {
            return ChildPreparation.fatal("delegate factory returned null for " + modType.id());
        }
        try {
            IBatchDelegate.PreparationResult result = PreparationMessageScope.prepare(
                    delegate, player, recipeId, machine.dim(), machine.pos());
            if (result.state() == IBatchDelegate.PreparationState.RETRY) {
                return ChildPreparation.retry(result.detail());
            }
            if (result.state() == IBatchDelegate.PreparationState.FATAL) {
                return ChildPreparation.fatal(result.detail());
            }
            configureDelegate(delegate, machine);
            return ChildPreparation.ready(delegate);
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI-ParallelGroup] Worker preparation failed at {}",
                    machine.pos(), e);
            return ChildPreparation.retry("worker preparation temporarily failed at " + machine.pos());
        }
    }

    private void configureDelegate(IBatchDelegate delegate, BoundMachine machine) {
        if (!(delegate instanceof AbstractBatchDelegate abstractDelegate)) return;
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
            armCaptureLegacy(worker);
            return true;
        }
        GraphConcurrencyPolicy.Decision concurrency = GraphConcurrencyPolicy.decide(
                modType.id(), delegate, concurrencyCapabilities);
        if (concurrency.exclusive()) {
            RSIntegrationMod.LOGGER.debug(
                    "[RSI-ParallelGroup] Rejecting capability-exclusive child delegate={} reason={}",
                    delegate.getClass().getSimpleName(), concurrency.reason());
            return false;
        }
        ItemStack expected = delegate.getExpectedOutput();
        var region = delegate.getOutputCaptureRegion();
        boolean ownsWorldCapture = concurrency.capabilities().outputOwnership()
                == BatchConcurrencyCapabilities.OutputOwnership.OWNED_WORLD_CAPTURE;
        if (expected != null && !expected.isEmpty() && !ownsWorldCapture) return false;
        OperationResourceCoordinator.CaptureRequest capture = ownsWorldCapture
                && expected != null && !expected.isEmpty() && region != null
                ? new OperationResourceCoordinator.CaptureRequest(worker.machine.dim(), region, expected)
                : null;
        List<MachineLeaseRegistry.MachineKey> machineScope = new ArrayList<>();
        machineScope.add(new MachineLeaseRegistry.MachineKey(
                worker.machine.dim(), worker.machine.pos(), modType.id()));
        for (BlockPos offset : concurrency.capabilities().supportOffsets()) {
            machineScope.add(new MachineLeaseRegistry.MachineKey(
                    worker.machine.dim(), worker.machine.pos().offset(offset), modType.id() + ":support"));
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

    private void armCaptureLegacy(WorkerSlot worker) {
        ItemStack expected = worker.delegate.getExpectedOutput();
        if (expected == null || expected.isEmpty()) return;
        var region = worker.delegate.getOutputCaptureRegion();
        if (region == null) return;
        ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, worker.machine.dim());
        CraftOutputInterceptor.CaptureHandle handle = CraftOutputInterceptor.arm(dimension, region, expected);
        if (handle == null) return;
        worker.operationSession = null;
        legacyCaptureHandles.put(worker.id, handle);
    }

    private boolean hasCapturedExpectedOutput(WorkerSlot worker) {
        ItemStack expected = worker.delegate.getExpectedOutput();
        if (expected == null || expected.isEmpty()) return false;
        List<ItemStack> captured;
        if (worker.operationSession != null) {
            captured = worker.operationSession.capturedSnapshot();
        } else {
            CraftOutputInterceptor.CaptureHandle handle = legacyCaptureHandles.get(worker.id);
            captured = handle == null ? List.of() : handle.snapshot();
        }
        return containsExpectedWorldOutput(captured, expected);
    }

    static boolean containsExpectedWorldOutput(List<ItemStack> captured, ItemStack expected) {
        if (expected == null || expected.isEmpty()) return false;
        int count = captured.stream()
                .filter(stack -> stack != null && !stack.isEmpty()
                        && ItemStack.isSameItemSameTags(stack, expected))
                .mapToInt(ItemStack::getCount)
                .sum();
        return count >= expected.getCount();
    }

    private List<ItemStack> drainCapture(WorkerSlot worker) {
        if (worker.operationSession != null) return worker.operationSession.drainCapture();
        CraftOutputInterceptor.CaptureHandle handle = legacyCaptureHandles.remove(worker.id);
        return handle == null ? List.of() : handle.drainAndClose();
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

    private void handleFailedStart(WorkerSlot worker, String detail) {
        List<ItemStack> captured = drainCapture(worker);
        if (!captured.isEmpty()) {
            int operationId = worker.operationId;
            settleReservation(operationId);
            settledResults.addAll(copyStacks(captured));
        }
        abandonUnstarted(worker);
        beginDraining(detail);
    }

    private void abandonUnstarted(WorkerSlot worker) {
        if (!worker.running()) return;
        operations.abandon(worker.id);
        worker.operationId = -1;
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
