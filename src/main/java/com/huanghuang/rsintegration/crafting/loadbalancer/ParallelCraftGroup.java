package com.huanghuang.rsintegration.crafting.loadbalancer;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.ModVersionDelegateRegistry;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.CraftOutputInterceptor;
import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.batch.AbstractBatchDelegate;
import com.huanghuang.rsintegration.crafting.batch.IBatchDelegate;
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
    private final List<ItemStack> settledResults = new ArrayList<>();
    private final ModType modType;
    private final ResourceLocation recipeId;
    private final OperationQueue operations;
    private final boolean[] safelyRecoverableVirtual;
    private BlockPos representativePos = BlockPos.ZERO;
    private MinecraftServer machineServer;
    private ServerPlayer player;
    private ExtractionLedger sharedLedger;
    private List<List<ItemStack>> operationMaterials = List.of();
    private List<List<ItemStack>> virtualDebits = List.of();
    private List<ExtractionLedger.ReservationToken> reservationTokens = List.of();
    private List<IngredientSpec> baseSpecs;
    private ItemStack targetOutput;
    private boolean sharedMaterialMode;
    private boolean started;
    private boolean draining;
    private boolean queuedMaterialsRecovered;
    private String failureDetail = "";

    private static final class WorkerSlot {
        final int id;
        final BoundMachine machine;
        IBatchDelegate delegate;
        CraftOutputInterceptor.CaptureHandle captureHandle;
        int operationId = -1;
        boolean pristineDelegate = true;
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
        this.modType = modType;
        this.recipeId = recipeId;
        this.player = player;
        this.operations = new OperationQueue(totalOperations);
        this.safelyRecoverableVirtual = new boolean[totalOperations];
        java.util.Arrays.fill(this.safelyRecoverableVirtual, true);
        int workerId = 0;
        for (BoundMachine machine : machines) {
            IBatchDelegate delegate = createAndValidateDelegate(machine, player);
            if (delegate == null) continue;
            workers.add(new WorkerSlot(workerId++, machine, delegate));
            if (representativePos.equals(BlockPos.ZERO)) representativePos = machine.pos();
        }
        if (!workers.isEmpty()) baseSpecs = workers.get(0).delegate.getRequiredMaterials();
        RSIntegrationMod.LOGGER.debug("[RSI-ParallelGroup] Created {}/{} workers for {} operations of {}",
                workers.size(), machines.size(), totalOperations, recipeId);
    }

    @Override
    public boolean validateAndInit(ServerPlayer player, ResourceLocation recipeId,
                                   @Nullable ResourceLocation dim, BlockPos pos) {
        return workers.size() >= 2;
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

    public void setReservationTokens(List<ExtractionLedger.ReservationToken> tokens) {
        this.reservationTokens = List.copyOf(tokens);
    }

    public void setVirtualDebits(List<List<ItemStack>> debits) {
        List<List<ItemStack>> copies = new ArrayList<>(debits.size());
        for (List<ItemStack> debit : debits) copies.add(List.copyOf(copyStacks(debit)));
        this.virtualDebits = List.copyOf(copies);
    }

    @Override
    public boolean tryStartWithMaterials(ServerPlayer player, List<ItemStack> materials,
                                         ExtractionLedger sharedLedger) {
        if (baseSpecs == null || baseSpecs.isEmpty()) return false;
        int perOperation = baseSpecs.size();
        if (materials.size() != perOperation * operations.totalOperations()
                || reservationTokens.size() != operations.totalOperations()
                || virtualDebits.size() != operations.totalOperations()) {
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
        int startedCount = 0;
        for (WorkerSlot worker : workers) {
            if (startNext(worker)) startedCount++;
            if (operations.queuedOperations() == 0) break;
        }
        started = startedCount > 0;
        if (!started) beginDraining("no workers accepted an operation");
        return started;
    }

    private boolean startNext(WorkerSlot worker) {
        int operationId = operations.claim(worker.id);
        if (operationId < 0) return false;

        IBatchDelegate delegate = worker.pristineDelegate
                ? worker.delegate : createAndValidateDelegate(worker.machine, player);
        worker.pristineDelegate = false;
        if (delegate == null) {
            operations.abandon(worker.id);
            worker.operationId = -1;
            beginDraining("worker validation failed at " + worker.machine.pos());
            return false;
        }
        worker.delegate = delegate;
        worker.operationId = operationId;
        // Once start is attempted, the delegate may already have moved this
        // operation's virtual inputs into the physical machine. Never synthesize
        // those inputs back unless the operation was never dispatched.
        safelyRecoverableVirtual[operationId] = false;
        worker.needsFailureCleanup = false;
        armCapture(worker);
        try {
            boolean accepted;
            if (sharedMaterialMode) {
                if (delegate instanceof AbstractBatchDelegate abstractDelegate) {
                    abstractDelegate.useSharedLedger(sharedLedger);
                }
                accepted = delegate.tryStartWithMaterials(player,
                        copyStacksKeepingEmpty(operationMaterials.get(operationId)), sharedLedger);
            } else {
                accepted = delegate.tryStartSingleCraft(player);
            }
            if (!accepted) {
                handleFailedStart(worker, "worker start failed at " + worker.machine.pos());
                return false;
            }
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
                worker.needsFailureCleanup = true;
                beginDraining("worker observation failed at " + worker.machine.pos());
                continue;
            }
            if (observation.phase() == CraftPhase.FAILED) {
                operations.abandon(worker.id);
                worker.operationId = -1;
                worker.needsFailureCleanup = true;
                beginDraining(worker.machine.pos() + ": " + observation.detail());
                continue;
            }
            if (observation.phase() == CraftPhase.DONE) {
                settleCompletedOperation(worker);
            }
        }

        if (draining && operations.isDrained()) {
            return new CraftObservation(CraftPhase.FAILED, failureDetail);
        }
        if (operations.isComplete()) return new CraftObservation(CraftPhase.DONE);
        return new CraftObservation(CraftPhase.WORKING);
    }

    private boolean settleCompletedOperation(WorkerSlot worker) {
        int operationId = worker.operationId;
        List<ItemStack> actual = new ArrayList<>(drainCapture(worker));
        try {
            actual.addAll(worker.delegate.collectAllResults(player));
        } catch (Exception e) {
            operations.abandon(worker.id);
            worker.operationId = -1;
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
            worker.needsFailureCleanup = true;
            beginDraining("worker output was externally extracted at " + worker.machine.pos());
            return false;
        }
        ItemStack expectedWorld = worker.delegate.getExpectedOutput();
        if (expected == null && expectedWorld != null && !expectedWorld.isEmpty() && actual.isEmpty()) {
            settleReservation(operationId);
            operations.abandon(worker.id);
            worker.operationId = -1;
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
            worker.needsFailureCleanup = true;
            beginDraining("worker finish cleanup failed at " + worker.machine.pos());
            return false;
        }
        operations.complete(worker.id);
        worker.operationId = -1;

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

    public boolean isDraining() {
        return draining;
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
        for (WorkerSlot worker : workers) drainCapture(worker);
    }

    @Override
    public BlockPos getMachinePos() {
        return representativePos;
    }

    public int getChildCount() {
        return workers.size();
    }

    public void setMachineServer(MinecraftServer server) {
        this.machineServer = server;
        for (WorkerSlot worker : workers) configureDelegate(worker.delegate, worker.machine);
    }

    public void setTargetOutput(@Nullable ItemStack targetOutput) {
        this.targetOutput = targetOutput == null || targetOutput.isEmpty() ? null : targetOutput.copy();
        for (WorkerSlot worker : workers) configureDelegate(worker.delegate, worker.machine);
    }

    private IBatchDelegate createAndValidateDelegate(BoundMachine machine, ServerPlayer player) {
        IBatchDelegate delegate = createChildDelegate(modType);
        if (delegate == null) return null;
        try {
            if (!delegate.validateAndInit(player, recipeId, machine.dim(), machine.pos())) return null;
            configureDelegate(delegate, machine);
            return delegate;
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI-ParallelGroup] Worker validation failed at {}",
                    machine.pos(), e);
            return null;
        }
    }

    private void configureDelegate(IBatchDelegate delegate, BoundMachine machine) {
        if (!(delegate instanceof AbstractBatchDelegate abstractDelegate)) return;
        abstractDelegate.setMachineDim(machine.dim());
        if (machineServer != null) abstractDelegate.setMachineServer(machineServer);
        if (targetOutput != null) abstractDelegate.setTargetOutput(targetOutput);
    }

    private void armCapture(WorkerSlot worker) {
        ItemStack expected = worker.delegate.getExpectedOutput();
        if (expected == null || expected.isEmpty()) return;
        var region = worker.delegate.getOutputCaptureRegion();
        if (region == null) return;
        ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, worker.machine.dim());
        worker.captureHandle = CraftOutputInterceptor.arm(dimension, region, expected);
    }

    private static List<ItemStack> drainCapture(WorkerSlot worker) {
        CraftOutputInterceptor.CaptureHandle handle = worker.captureHandle;
        worker.captureHandle = null;
        return handle == null ? List.of() : handle.drainAndClose();
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

    private static IBatchDelegate createChildDelegate(ModType type) {
        if (type == ModType.GENERIC) return null;
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
