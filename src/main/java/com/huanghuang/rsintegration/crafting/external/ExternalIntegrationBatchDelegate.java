package com.huanghuang.rsintegration.crafting.external;

import com.huanghuang.rsintegration.api.IntegrationResourceKey;
import com.huanghuang.rsintegration.api.RSIIntegrationApi;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.api.diagnostic.IntegrationDiagnostic;
import com.huanghuang.rsintegration.api.execution.CollectedOutput;
import com.huanghuang.rsintegration.api.execution.ExecutionClaim;
import com.huanghuang.rsintegration.api.execution.ExecutionPlan;
import com.huanghuang.rsintegration.api.execution.ExecutionPlanResult;
import com.huanghuang.rsintegration.api.execution.ExecutionRequestContext;
import com.huanghuang.rsintegration.api.execution.ExecutionResult;
import com.huanghuang.rsintegration.api.execution.ExecutionStartStatus;
import com.huanghuang.rsintegration.api.execution.FailureReason;
import com.huanghuang.rsintegration.api.execution.LifecyclePhase;
import com.huanghuang.rsintegration.api.execution.MaterialDisposition;
import com.huanghuang.rsintegration.api.execution.MaterialOutcome;
import com.huanghuang.rsintegration.api.execution.OperationCleanupResult;
import com.huanghuang.rsintegration.api.execution.OperationCollection;
import com.huanghuang.rsintegration.api.execution.OperationContext;
import com.huanghuang.rsintegration.api.execution.RecipeExecutionIntegration;
import com.huanghuang.rsintegration.api.execution.ReservedMaterial;
import com.huanghuang.rsintegration.api.execution.ReservedMaterialView;
import com.huanghuang.rsintegration.api.execution.RunningOperation;
import com.huanghuang.rsintegration.api.machine.InputOutputAccess;
import com.huanghuang.rsintegration.api.machine.ItemPortAccess;
import com.huanghuang.rsintegration.api.machine.FluidPortAccess;
import com.huanghuang.rsintegration.api.machine.MachineClaim;
import com.huanghuang.rsintegration.api.machine.MachineContext;
import com.huanghuang.rsintegration.api.machine.MachineDescriptor;
import com.huanghuang.rsintegration.api.machine.MachineDescriptorResult;
import com.huanghuang.rsintegration.api.machine.MachineExecutionMode;
import com.huanghuang.rsintegration.api.machine.MachineIntegration;
import com.huanghuang.rsintegration.api.machine.MachineItemPort;
import com.huanghuang.rsintegration.api.machine.MachineFluidPort;
import com.huanghuang.rsintegration.api.machine.MachineState;
import com.huanghuang.rsintegration.api.machine.MachineStateProvider;
import com.huanghuang.rsintegration.api.recipe.MaterialRequirement;
import com.huanghuang.rsintegration.api.recipe.FluidRequirement;
import com.huanghuang.rsintegration.api.recipe.OutputDeclaration;
import com.huanghuang.rsintegration.api.recipe.OutputKind;
import com.huanghuang.rsintegration.api.recipe.OutputProbabilityMode;
import com.huanghuang.rsintegration.api.recipe.RecipeAvailability;
import com.huanghuang.rsintegration.api.recipe.RecipeAvailabilityContext;
import com.huanghuang.rsintegration.api.recipe.RecipeAvailabilityProvider;
import com.huanghuang.rsintegration.api.recipe.RecipeContext;
import com.huanghuang.rsintegration.api.recipe.RecipeExecutionKind;
import com.huanghuang.rsintegration.api.recipe.RecipeIntegration;
import com.huanghuang.rsintegration.api.recipe.RecipeView;
import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.batch.AbstractBatchDelegate;
import com.huanghuang.rsintegration.crafting.batch.OperationStartContext;
import com.huanghuang.rsintegration.crafting.batch.OutputContract;
import com.huanghuang.rsintegration.crafting.graph.OutputProbability;
import com.huanghuang.rsintegration.storage.StorageFluidKey;
import com.huanghuang.rsintegration.storage.StorageFluidOperationResult;
import com.huanghuang.rsintegration.storage.StorageFluidSnapshot;
import com.huanghuang.rsintegration.storage.StoredFluid;
import com.huanghuang.rsintegration.storage.StorageCapability;
import com.huanghuang.rsintegration.storage.StorageOperationStatus;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.fluids.capability.IFluidHandler.FluidAction;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 公共 Integration API 的图节点执行适配器。
 *
 * <p>物品标准 I/O 在 RSI 主线程完成端口容量检查、投料、状态观察和
 * 真实产物收取。无法无损落入标准物品端口的声明在这里显式拒绝。</p>
 */
public final class ExternalIntegrationBatchDelegate extends AbstractBatchDelegate {
    private final Supplier<RSIIntegrationApi> apiProvider;
    private final boolean retainChunks;
    private ServerLevel level;
    private BlockPos machinePos;
    private Recipe<?> recipe;
    private RecipeIntegration<?> recipeIntegration;
    private RecipeView recipeView;
    private MachineIntegration machineIntegration;
    private MachineDescriptor descriptor;
    private MachineStateProvider stateProvider;
    private RecipeExecutionKind executionKind;
    private RecipeExecutionIntegration executionIntegration;
    private ExecutionPlan executionPlan;
    private RunningOperation runningOperation;
    private OperationStartContext operationStartContext;
    private ReservedMaterialView customMaterials;
    private MachineContext customMachineContext;
    private boolean customStartPending;
    private boolean customPendingRefundSafe;
    private long lastCustomStateRevision = -1L;
    private long lastCollectionSequence;
    private long recipeRevision;
    private boolean operationStarted;
    private boolean resultsCollected;
    private long lastStateRevision = -1L;
    private long startedAtTick;
    @Nullable
    private Component preparationFailure;
    private final Map<SlotKey, ItemStack> inputBaseline = new LinkedHashMap<>();
    private final Map<SlotKey, ItemStack> outputBaseline = new LinkedHashMap<>();
    private final Map<FluidSlotKey, FluidStack> fluidInputBaseline =
            new LinkedHashMap<>();
    private final Map<FluidSlotKey, FluidStack> fluidOutputBaseline =
            new LinkedHashMap<>();
    private final List<InsertedMaterial> insertedMaterials = new ArrayList<>();
    private final List<ItemStack> uninsertedMaterials = new ArrayList<>();
    private final List<ItemStack> pendingInputRefunds = new ArrayList<>();

    public ExternalIntegrationBatchDelegate() {
        this(() -> RSIIntegrationApi.get().orElse(null), true);
    }

    ExternalIntegrationBatchDelegate(RSIIntegrationApi api) {
        this(() -> api, false);
    }

    private ExternalIntegrationBatchDelegate(
            Supplier<RSIIntegrationApi> apiProvider,
            boolean retainChunks
    ) {
        this.apiProvider = apiProvider;
        this.retainChunks = retainChunks;
    }

    @Override
    public boolean startOperation(OperationStartContext context) {
        this.operationStartContext = context;
        return super.startOperation(context);
    }

    @Override
    public PreparationResult prepare(
            ServerPlayer player,
            ResourceLocation recipeId,
            @Nullable ResourceLocation dim,
            BlockPos pos
    ) {
        preparationFailure = null;
        if (validateAndInit(player, recipeId, dim, pos)) {
            return PreparationResult.ready();
        }
        Component message = preparationFailure != null
                ? preparationFailure
                : Component.literal("RSI Integration machine is not ready");
        return PreparationResult.waiting(
                "integration_machine_preparation_failed", message);
    }

    @Override
    public boolean validateAndInit(
            ServerPlayer player,
            ResourceLocation recipeId,
            @Nullable ResourceLocation dim,
            BlockPos pos
    ) {
        RSIIntegrationApi api = apiProvider.get();
        if (api == null) {
            preparationFailure = Component.literal(
                    "RSI Integration API is unavailable");
            return false;
        }
        level = CraftPacketUtils.resolveLevel(player.server, dim, player);
        if (level == null || !level.hasChunkAt(pos)) {
            preparationFailure = Component.literal(
                    "RSI Integration machine chunk is not loaded");
            return false;
        }
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity == null || blockEntity.isRemoved()) {
            preparationFailure = Component.literal(
                    "RSI Integration machine block entity is missing");
            return false;
        }
        Recipe<?> found = level.getRecipeManager().byKey(recipeId).orElse(null);
        if (found == null) {
            preparationFailure = Component.literal(
                    "RSI Integration recipe is missing: " + recipeId);
            return false;
        }
        var recipeClaim = api.recipes().claim(found);
        if (recipeClaim.winner() == null) {
            preparationFailure = firstDiagnostic(
                    recipeClaim.diagnostics(),
                    "RSI Integration recipe has no unique owner");
            return false;
        }
        ExternalRecipeProjection.Result projection =
                ExternalRecipeProjection.inspect(
                        recipeClaim.winner(),
                        found,
                        level.registryAccess()
                );
        if (!projection.available()) {
            preparationFailure = firstDiagnostic(
                    projection.diagnostics(),
                    "RSI Integration recipe projection failed");
            return false;
        }
        MachineClaim machineClaim = api.machines().claim(blockEntity);
        if (machineClaim.winner() == null) {
            preparationFailure = firstDiagnostic(
                    machineClaim.diagnostics(),
                    "RSI Integration machine has no unique owner");
            return false;
        }
        MachineIntegration machine = machineClaim.winner();
        MachineContext machineContext = new MachineContext(
                machine.id(), level, pos.immutable(), player);
        MachineDescriptorResult described =
                machine.describe(machineContext, blockEntity);
        if (described.descriptor() == null) {
            preparationFailure = firstDiagnostic(
                    described.diagnostics(),
                    "RSI Integration machine descriptor is unavailable");
            return false;
        }
        MachineDescriptor machineDescriptor = described.descriptor();
        if (!machineDescriptor.acceptedProcesses().contains(
                projection.view().processId())) {
            preparationFailure = Component.literal(
                    "RSI Integration machine does not accept process "
                            + projection.view().processId());
            return false;
        }
        RecipeExecutionKind executionKind = recipeClaim.winner().executionKind();
        if (executionKind == RecipeExecutionKind.NONE) {
            preparationFailure = Component.literal(
                    "RSI Integration recipe is display-only");
            return false;
        }
        boolean customRecipe = executionKind == RecipeExecutionKind.CUSTOM;
        boolean customMachine = machineDescriptor.executionMode()
                == MachineExecutionMode.CUSTOM;
        if (customRecipe != customMachine) {
            preparationFailure = Component.literal(
                    "RSI Integration recipe and machine use different execution modes");
            return false;
        }
        RecipeExecutionIntegration custom = null;
        if (customRecipe) {
            ExecutionClaim executionClaim = api.executions().claim(
                    projection.view(), machineContext);
            if (executionClaim.winner() == null) {
                preparationFailure = firstDiagnostic(
                        executionClaim.diagnostics(),
                        "RSI Integration custom execution has no unique owner");
                return false;
            }
            custom = executionClaim.winner();
        }
        if (!customRecipe) {
            List<IntegrationDiagnosticMessage> validation =
                    validatePorts(projection.view(), machineDescriptor);
            if (!validation.isEmpty()) {
                preparationFailure = Component.literal(
                        validation.get(0).message());
                return false;
            }
        }
        RecipeAvailabilityProvider availability =
                recipeClaim.winner().availabilityProvider().orElse(null);
        if (availability != null) {
            RecipeAvailability result = availability.check(
                    new RecipeAvailabilityContext(
                            new RecipeContext(
                                    recipeClaim.winner().id(),
                                    level.registryAccess(),
                                    projection.revision()
                            ),
                            found,
                            projection.view(),
                            player,
                            machineContext,
                            targetOutput == null || targetOutput.isEmpty()
                                    ? null
                                    : IntegrationResourceKey.item(targetOutput)
                    )
            );
            if (!result.executionAvailable()) {
                preparationFailure = firstDiagnostic(
                        result.diagnostics(),
                        "RSI Integration recipe is temporarily unavailable");
                return false;
            }
        }
        this.machinePos = pos.immutable();
        this.recipe = found;
        this.recipeIntegration = recipeClaim.winner();
        this.recipeView = projection.view();
        this.machineIntegration = machine;
        this.descriptor = machineDescriptor;
        this.stateProvider = machine.stateProvider().orElse(null);
        this.executionKind = executionKind;
        this.executionIntegration = custom;
        this.executionPlan = null;
        this.runningOperation = null;
        this.operationStartContext = null;
        this.customMaterials = null;
        this.customMachineContext = null;
        this.customStartPending = false;
        this.customPendingRefundSafe = false;
        this.lastCustomStateRevision = -1L;
        this.lastCollectionSequence = 0L;
        this.recipeRevision = projection.revision();
        this.operationStarted = false;
        this.resultsCollected = false;
        this.lastStateRevision = -1L;
        this.inputBaseline.clear();
        this.outputBaseline.clear();
        this.fluidInputBaseline.clear();
        this.fluidOutputBaseline.clear();
        this.insertedMaterials.clear();
        this.uninsertedMaterials.clear();
        this.pendingInputRefunds.clear();
        setMachineDim(level.dimension().location());
        setMachineServer(player.server);
        if (!customRecipe
                && machineDescriptor.executionMode() == MachineExecutionMode.TICKED_AND_BUSY
                && stateProvider == null) {
            preparationFailure = Component.literal(
                    "RSI Integration TICKED_AND_BUSY machine has no state provider");
            return false;
        }
        return true;
    }

    @Override
    @Nullable
    public List<IngredientSpec> getRequiredMaterials() {
        if (recipeView == null) return null;
        try {
            return ExternalRecipeProjection.ingredients(recipeView);
        } catch (IllegalArgumentException exception) {
            preparationFailure = Component.literal(exception.getMessage());
            return null;
        }
    }

    @Override
    public boolean startPreparedOperation(
            ServerPlayer player,
            List<ItemStack> materials,
            ExtractionLedger sharedLedger
    ) {
        if (!validateExecutionContext(player)
                || recipeView == null || descriptor == null) {
            return false;
        }
        if (executionKind == RecipeExecutionKind.CUSTOM) {
            return startCustomOperation(player, materials, sharedLedger);
        }
        this.ledger = sharedLedger;
        this.operationStarted = false;
        this.resultsCollected = false;
        this.startedAtTick = level.getGameTime();
        this.insertedMaterials.clear();
        this.uninsertedMaterials.clear();
        this.pendingInputRefunds.clear();
        for (ItemStack stack : materials) {
            if (!stack.isEmpty()) uninsertedMaterials.add(stack.copy());
        }
        if (materials.size() < recipeView.materials().size()) {
            preparationFailure = Component.literal(
                    "RSI Integration prepared material list is incomplete");
            return false;
        }
        snapshotSlots(inputBaseline,
                descriptor.itemPorts().stream()
                        .filter(port -> port.direction() == InputOutputAccess.INPUT)
                        .toList());
        snapshotSlots(outputBaseline,
                descriptor.itemPorts().stream()
                        .filter(port -> port.direction() == InputOutputAccess.OUTPUT)
                        .toList());
        snapshotFluidSlots(
                fluidInputBaseline,
                descriptor.fluidPorts().stream()
                        .filter(port ->
                                port.direction() == InputOutputAccess.INPUT)
                        .toList()
        );
        snapshotFluidSlots(
                fluidOutputBaseline,
                descriptor.fluidPorts().stream()
                        .filter(port ->
                                port.direction() == InputOutputAccess.OUTPUT)
                        .toList()
        );
        if (!hasOutputCapacity(player)) {
            preparationFailure = Component.literal(
                    "RSI Integration machine output ports have insufficient capacity");
            return false;
        }
        for (int index = 0; index < recipeView.materials().size(); index++) {
            MaterialRequirement requirement = recipeView.materials().get(index);
            ItemStack stack = materials.get(index).copy();
            if (stack.isEmpty()) {
                preparationFailure = Component.literal(
                        "RSI Integration prepared material is empty: "
                                + requirement.entryId());
                return false;
            }
            int existing = matchingInputCount(requirement, stack);
            if (existing > 0) {
                int used = Math.min(existing, stack.getCount());
                pendingInputRefunds.add(stack.copyWithCount(used));
                stack.shrink(used);
            }
            if (stack.isEmpty()) continue;
            ItemStack remainder = insertMaterial(
                    player, requirement, stack);
            if (!remainder.isEmpty()) {
                preparationFailure = Component.literal(
                        "RSI Integration input port rejected "
                                + requirement.entryId());
                return false;
            }
            int inserted = stack.getCount() - remainder.getCount();
            removeUninserted(requirement.entryId(), stack, inserted);
        }
        if (!insertFluidInputs(player)) return false;
        markCraftStarted();
        this.operationStarted = true;
        retainMachineChunk();
        return true;
    }

    @Override
    protected CraftObservation observeMachineCraft(
            ServerLevel level,
            BlockEntity blockEntity
    ) {
        if (!operationStarted) return workingObservation();
        if (executionKind == RecipeExecutionKind.CUSTOM) {
            return observeCustomOperation(level);
        }
        if (stateProvider != null) {
            MachineState state;
            try {
                state = stateProvider.readState(
                        new MachineContext(
                                machineIntegration.id(),
                                level,
                                machinePos,
                                null
                        ),
                        blockEntity
                );
            } catch (RuntimeException exception) {
                return failObservation(
                        "state provider failed: " + exception.getClass().getName());
            }
            if (state.stateRevision() < lastStateRevision) {
                return failObservation("machine state revision moved backwards");
            }
            lastStateRevision = state.stateRevision();
            if (!state.loaded() || !state.valid()) {
                return failObservation("machine is unloaded or invalid");
            }
            if (state.failure() != null) {
                return failObservation(
                        "machine reported " + state.failure().code());
            }
            if (state.busy()) {
                if (state.activeRecipeId() != null
                        && !recipe.getId().equals(state.activeRecipeId())) {
                    return failObservation(
                            "machine active recipe mismatch");
                }
                if (state.activeProcessId() != null
                        && !recipeView.processId().equals(
                        state.activeProcessId())) {
                    return failObservation(
                            "machine active process mismatch");
                }
                return workingObservation();
            }
            if (state.totalProgress() > 0
                    && state.progress() >= state.totalProgress()) {
                return doneObservation();
            }
        }
        if (hasNewExpectedOutput() || hasNewExpectedFluidOutput()) {
            return doneObservation();
        }
        if (executionKind == RecipeExecutionKind.STANDARD_IO
                && descriptor.executionMode()
                == MachineExecutionMode.IMMEDIATE_ON_INSERT
                && level.getGameTime() - startedAtTick > 1L) {
            return failObservation("machine produced no declared output");
        }
        return workingObservation();
    }

    @Override
    public ItemStack collectResult(ServerPlayer player) {
        List<ItemStack> results = collectAllResults(player);
        return results.isEmpty() ? ItemStack.EMPTY : results.get(0);
    }

    @Override
    public List<ItemStack> collectAllResults(ServerPlayer player) {
        if (resultsCollected || recipeView == null) return List.of();
        if (executionKind == RecipeExecutionKind.CUSTOM) {
            return collectCustomResults(player);
        }
        resultsCollected = true;
        Map<ItemStackKey, ItemStack> collected = new LinkedHashMap<>();
        for (OutputDeclaration output : recipeView.outputs()) {
            if (output.resource().kind()
                    == IntegrationResourceKey.ResourceKind.FLUID) {
                continue;
            }
            long requested = output.amount().max();
            long remaining = Math.min(Integer.MAX_VALUE, requested);
            for (MachineItemPort port : outputPorts(output.outputGroup())) {
                if (remaining <= 0L) break;
                ItemStack actual = extractMatching(
                        port, output, (int) Math.min(
                                Integer.MAX_VALUE, remaining), player);
                if (actual.isEmpty()) continue;
                remaining -= actual.getCount();
                merge(collected, actual);
            }
        }
        collectFluidOutputs(player);
        return collected.values().stream()
                .map(ItemStack::copy)
                .toList();
    }

    @Override
    public boolean collectsPhysicalSecondaryOutputs() {
        return true;
    }

    @Override
    public OutputContract outputContract() {
        if (recipeView == null) return OutputContract.none();
        List<OutputContract.Port> ports = new ArrayList<>();
        for (OutputDeclaration output : recipeView.outputs()) {
            if (output.resource().kind()
                    != IntegrationResourceKey.ResourceKind.ITEM) {
                continue;
            }
            long max = output.amount().max();
            if (max > Integer.MAX_VALUE) return OutputContract.none();
            ItemStack prototype = ExternalRecipeProjection.toStack(
                    output.resource(), 1L);
            ports.add(new OutputContract.Port(
                    output.portId().toString(),
                    null,
                    prototype,
                    (int) max,
                    output.outputKind()
                            == OutputKind.PRIMARY
                            ? OutputContract.Kind.PRIMARY
                            : OutputContract.Kind.SECONDARY,
                    OutputContract.Source.SLOT,
                    (int) output.amount().min(),
                    (int) max,
                    probability(output),
                    output.dynamicIdentity(),
                    output.sourceInputEntryId()
            ));
        }
        return new OutputContract(ports);
    }

    @Override
    public ExpectedProduction getExpectedProduction() {
        if (!ExternalRecipeProjection.deterministicPrimary(recipeView)) {
            return null;
        }
        ItemStack primary = ExternalRecipeProjection.primaryStack(recipeView);
        return primary.isEmpty()
                ? null
                : new ExpectedProduction(primary, primary.getCount());
    }

    @Override
    public boolean publishesDeclaredGraphOutputs() {
        return true;
    }

    @Override
    public boolean canCollectResultWithoutWorldCapture() {
        return true;
    }

    @Override
    protected void clearMachineState(BlockEntity blockEntity, ServerPlayer player) {
        if (executionKind == RecipeExecutionKind.CUSTOM) {
            clearCustomOperation(player);
            return;
        }
        List<ItemStack> recovered = new ArrayList<>();
        List<FluidStack> recoveredFluids = new ArrayList<>();
        for (ItemStack uninserted : uninsertedMaterials) {
            if (uninserted != null && !uninserted.isEmpty()) {
                recovered.add(uninserted.copy());
            }
        }
        for (MachineItemPort port : descriptor == null
                ? List.<MachineItemPort>of()
                : descriptor.itemPorts()) {
            if (port.direction() != InputOutputAccess.INPUT) continue;
            SlotKey key = new SlotKey(port);
            ItemStack baseline = inputBaseline.get(key);
            int baselineCount = baseline == null ? 0 : baseline.getCount();
            int current = count(port, player);
            int removable = Math.max(0, current - baselineCount);
            if (removable <= 0) continue;
            ItemStack extracted = extract(port, removable, player);
            if (!extracted.isEmpty()) recovered.add(extracted);
        }
        if (player != null && descriptor != null) {
            for (var port : descriptor.fluidPorts()) {
                if (port.direction() != InputOutputAccess.INPUT) continue;
                FluidStack baseline = fluidInputBaseline.get(
                        new FluidSlotKey(port));
                int protectedAmount = baseline == null
                        ? 0 : baseline.getAmount();
                FluidStack current = getFluid(port);
                int removable = Math.max(
                        0, current.getAmount() - protectedAmount);
                if (removable <= 0) continue;
                FluidStack extracted = extractFluid(port, removable);
                if (!extracted.isEmpty()) recoveredFluids.add(extracted);
            }
            refundFluids(player, recoveredFluids);
        }
        recordFailureRecoveredInputs(recovered);
    }

    @Override
    protected void clearMissingMachineState(@Nullable ServerPlayer player) {
        if (executionKind == RecipeExecutionKind.CUSTOM) {
            clearCustomOperation(player);
            return;
        }
        recordFailureRecoveredInputs(uninsertedMaterials);
    }

    @Override
    protected boolean isFailureRefundSafe() {
        if (executionKind == RecipeExecutionKind.CUSTOM) {
            return customPendingRefundSafe;
        }
        long inserted = insertedMaterials.stream()
                .mapToLong(InsertedMaterial::count)
                .sum();
        long recovered = failureRecoveredInputs() == null
                ? 0L
                : failureRecoveredInputs().stream()
                .mapToLong(ItemStack::getCount)
                .sum();
        return inserted == recovered;
    }

    @Override
    public void onBatchFinished(@Nullable ServerPlayer player) {
        if (!markTerminalCleanup()) return;
        if (runningOperation != null) {
            runningOperation.onSettled(player);
            runningOperation = null;
        }
        flushPendingInputRefunds(player);
        uninsertedMaterials.clear();
        pendingInputRefunds.clear();
        releaseReusableMaterials(player);
        resetState();
    }

    @Override
    public BlockPos getMachinePos() {
        return machinePos;
    }

    private void retainMachineChunk() {
        if (retainChunks) {
            forceMachineChunk(level, machinePos, true);
        }
    }

    private boolean startCustomOperation(
            ServerPlayer player,
            List<ItemStack> materials,
            ExtractionLedger sharedLedger
    ) {
        if (operationStartContext == null
                || operationStartContext.transactionId() == null
                || operationStartContext.operationId() == null) {
            preparationFailure = Component.literal(
                    "RSI Integration custom operation identity is missing");
            return false;
        }
        this.ledger = sharedLedger;
        List<ReservedMaterial> reserved = new ArrayList<>();
        for (int index = 0; index < recipeView.materials().size(); index++) {
            MaterialRequirement requirement = recipeView.materials().get(index);
            ItemStack stack = materials.get(index);
            if (stack.isEmpty()) {
                preparationFailure = Component.literal(
                        "RSI Integration reserved material is empty: "
                                + requirement.entryId());
                return false;
            }
            reserved.add(new ReservedMaterial(
                    requirement.entryId().toString(),
                    IntegrationResourceKey.item(stack),
                    stack.getCount(),
                    requirement.consumption()
            ));
        }
        ReservedMaterialView materialView = new ReservedMaterials(reserved);
        MachineContext machineContext = new MachineContext(
                machineIntegration.id(), level, machinePos, player);
        this.customMaterials = materialView;
        this.customMachineContext = machineContext;
        RecipeContext recipeContext = new RecipeContext(
                recipeIntegration.id(), level.registryAccess(), recipeRevision);
        ExecutionPlanResult planned = executionIntegration.createPlan(
                new ExecutionRequestContext(
                        recipeContext,
                        recipe,
                        recipeView,
                        machineContext,
                        materialView,
                        operationStartContext.transactionId(),
                        operationStartContext.operationId(),
                        operationStartContext.attempt(),
                        targetOutput == null || targetOutput.isEmpty()
                                ? null
                                : IntegrationResourceKey.item(targetOutput)
                )
        );
        if (planned.plan() == null) {
            preparationFailure = firstDiagnostic(
                    planned.diagnostics(),
                    "RSI custom execution rejected its plan");
            return false;
        }
        String planMismatch = validateCustomPlan(planned.plan());
        if (planMismatch != null) {
            preparationFailure = Component.literal(planMismatch);
            return false;
        }
        this.executionPlan = planned.plan();
        ExecutionResult started = startCustomIntegration(player);
        if (started.status() == ExecutionStartStatus.RETRY) {
            this.customStartPending = true;
            this.startedAtTick = level.getGameTime();
            markCraftStarted();
            this.operationStarted = true;
            retainMachineChunk();
            return true;
        }
        if (started.status() != ExecutionStartStatus.STARTED
                || started.operation() == null) {
            preparationFailure = firstDiagnostic(
                    started.diagnostics(),
                    "RSI custom execution rejected start");
            return false;
        }
        this.runningOperation = started.operation();
        this.customStartPending = false;
        this.startedAtTick = level.getGameTime();
        markCraftStarted();
        this.operationStarted = true;
        retainMachineChunk();
        return true;
    }

    private ExecutionResult startCustomIntegration(ServerPlayer player) {
        return executionIntegration.start(
                new OperationContext(
                        player,
                        operationStartContext.transactionId(),
                        operationStartContext.operationId(),
                        operationStartContext.attempt(),
                        customMaterials,
                        customMachineContext,
                        level.getGameTime()
                ),
                executionPlan
        );
    }

    @Nullable
    private String validateCustomPlan(ExecutionPlan plan) {
        if (!plan.operationId().equals(operationStartContext.operationId())) {
            return "RSI custom plan operation id does not match the dispatch";
        }
        if (!plan.recipeId().equals(recipe.getId())
                || !plan.machineId().equals(descriptor.machineId())
                || !plan.recipeIntegrationId().equals(recipeIntegration.id())
                || !plan.machineIntegrationId().equals(machineIntegration.id())
                || !plan.executionIntegrationId().equals(executionIntegration.id())) {
            return "RSI custom plan identity does not match the claimed integrations";
        }
        if (plan.recipeRevision() != recipeRevision
                || plan.descriptorRevision() != descriptor.descriptorRevision()
                || !plan.recipeFingerprint().equals(
                recipeView.definitionFingerprint())
                || !plan.descriptorFingerprint().equals(
                descriptor.descriptorFingerprint())) {
            return "RSI custom plan snapshot is stale";
        }
        return null;
    }

    private CraftObservation observeCustomOperation(ServerLevel level) {
        if (customStartPending) {
            ExecutionResult started = startCustomIntegration(
                    operationStartContext.player());
            if (started.status() == ExecutionStartStatus.RETRY) {
                return workingObservation();
            }
            if (started.status() != ExecutionStartStatus.STARTED
                    || started.operation() == null) {
                return failObservation(
                        "custom execution rejected delayed start");
            }
            runningOperation = started.operation();
            customStartPending = false;
        }
        if (runningOperation == null) {
            return failObservation("custom operation is missing");
        }
        var observation = runningOperation.observe(level);
        if (lastCustomStateRevision >= 0L
                && observation.stateRevision() < lastCustomStateRevision) {
            return failObservation("custom state revision moved backwards");
        }
        lastCustomStateRevision = observation.stateRevision();
        return switch (observation.phase()) {
            case QUEUED, PREPARING, WAITING_FOR_MACHINE, WORKING ->
                    workingObservation();
            case READY_TO_COLLECT, COLLECTING, SETTLING, SUCCEEDED ->
                    doneObservation();
            case FAILED, CANCELLED ->
                    failObservation("custom operation reported "
                            + observation.phase());
            case CANCELLING ->
                    failObservation("custom operation is cancelling");
        };
    }

    private List<ItemStack> collectCustomResults(ServerPlayer player) {
        if (runningOperation == null) return List.of();
        OperationCollection collection = runningOperation.collect(player);
        if (collection.collectionSequence() <= lastCollectionSequence) {
            return List.of();
        }
        lastCollectionSequence = collection.collectionSequence();
        if (!collection.complete()) return List.of();
        Map<ItemStackKey, ItemStack> collected = new LinkedHashMap<>();
        for (CollectedOutput output : collection.outputs()) {
            if (output.resource().kind()
                    != IntegrationResourceKey.ResourceKind.ITEM) {
                return List.of();
            }
            merge(collected, ExternalRecipeProjection.toStack(
                    output.resource(), output.amount()));
        }
        resultsCollected = true;
        return collected.values().stream()
                .map(ItemStack::copy)
                .toList();
    }

    private void collectFluidOutputs(ServerPlayer player) {
        if (!hasStorageAccess()) return;
        for (OutputDeclaration output : recipeView.outputs()) {
            if (output.resource().kind()
                    != IntegrationResourceKey.ResourceKind.FLUID) {
                continue;
            }
            long remaining = Math.min(
                    Integer.MAX_VALUE, output.amount().max());
            for (var port : descriptor.fluidPorts()) {
                if (remaining <= 0L
                        || port.direction() != InputOutputAccess.OUTPUT
                        || !output.outputGroup().equals(
                        port.outputGroup())) {
                    continue;
                }
                FluidStack current = getFluid(port);
                if (current.isEmpty()
                        || !matchesFluid(current, output.resource())) {
                    continue;
                }
                FluidStack baseline = fluidOutputBaseline.get(
                        new FluidSlotKey(port));
                int protectedAmount = baseline != null
                        && matchesFluid(baseline, output.resource())
                        ? baseline.getAmount() : 0;
                int available = Math.max(
                        0, current.getAmount() - protectedAmount);
                if (available <= 0) continue;
                FluidStack extracted = extractFluid(
                        port, (int) Math.min(remaining, available));
                if (extracted.isEmpty()) continue;
                remaining -= extracted.getAmount();
                StorageFluidOperationResult inserted =
                        storageEndpoint().session().insertFluid(
                                player, extracted, false);
                if (inserted.remainder() != null
                        && !inserted.remainder().isEmpty()) {
                    RSIntegrationMod.LOGGER.error(
                            "[RSI-API] failed to insert collected fluid {} mB into storage",
                            inserted.remainder().getAmount());
                }
            }
        }
    }

    private static boolean matchesFluid(
            FluidStack stack,
            IntegrationResourceKey expected
    ) {
        if (stack == null || stack.isEmpty()
                || expected.kind()
                != IntegrationResourceKey.ResourceKind.FLUID) {
            return false;
        }
        return expected.id().equals(
                ForgeRegistries.FLUIDS.getKey(stack.getFluid()))
                && expected.identity().equals(
                stack.getTag() == null
                        ? new CompoundTag() : stack.getTag());
    }

    private void clearCustomOperation(@Nullable ServerPlayer player) {
        RunningOperation operation = runningOperation;
        runningOperation = null;
        if (operation == null) {
            if (customStartPending && customMaterials != null) {
                List<ItemStack> reserved = new ArrayList<>();
                for (ReservedMaterial material : customMaterials.materials()) {
                    if (material.resource().kind()
                            != IntegrationResourceKey.ResourceKind.ITEM) {
                        continue;
                    }
                    reserved.add(ExternalRecipeProjection.toStack(
                            material.resource(), material.amount()));
                }
                recordFailureRecoveredInputs(reserved);
                customPendingRefundSafe = true;
                customStartPending = false;
            }
            return;
        }
        try {
            OperationCleanupResult cleanup = operation.onFailure(
                    FailureReason.EXECUTION_EXCEPTION, player);
            List<ItemStack> recovered = new ArrayList<>();
            for (MaterialDisposition disposition : cleanup.materials()) {
                if (disposition.outcome() != MaterialOutcome.RECOVERED
                        || disposition.material().resource().kind()
                        != IntegrationResourceKey.ResourceKind.ITEM) {
                    continue;
                }
                recovered.add(ExternalRecipeProjection.toStack(
                        disposition.material().resource(),
                        disposition.material().amount()
                ));
            }
            recordFailureRecoveredInputs(recovered);
        } catch (RuntimeException exception) {
            RSIntegrationMod.LOGGER.warn(
                    "[RSI-API] custom cleanup failed for {}",
                    recipe == null ? "unknown" : recipe.getId(),
                    exception
            );
        }
    }

    private ItemStack insertMaterial(
            ServerPlayer player,
            MaterialRequirement requirement,
            ItemStack stack
    ) {
        List<MachineItemPort> ports = descriptor.itemPorts().stream()
                .filter(port -> port.direction() == InputOutputAccess.INPUT)
                .filter(port -> requirement.inputGroup().equals(
                        port.inputGroup()))
                .toList();
        if (ports.isEmpty()) return stack;
        ItemStack remainder = stack;
        for (MachineItemPort port : ports) {
            if (remainder.isEmpty()) break;
            ItemStack inserted = insert(port, remainder, player);
            int insertedCount = remainder.getCount() - inserted.getCount();
            if (insertedCount > 0) {
                insertedMaterials.add(new InsertedMaterial(
                        requirement.entryId(),
                        new SlotKey(port),
                        ItemStackKey.of(remainder),
                        insertedCount
                ));
            }
            remainder = inserted;
        }
        return remainder;
    }

    private boolean insertFluidInputs(ServerPlayer player) {
        if (recipeView.fluids().isEmpty()) return true;
        if (!hasStorageAccess()
                || !storageEndpoint().session().supports(
                StorageCapability.FLUID_STORAGE)) {
            preparationFailure = Component.literal(
                    "RSI Integration selected storage has no fluid capability");
            return false;
        }
        var snapshotResult = storageEndpoint().session().snapshotFluids(player);
        if (!snapshotResult.successful()) {
            preparationFailure = Component.literal(
                    "RSI Integration fluid snapshot failed: "
                            + snapshotResult.status());
            return false;
        }
        StorageFluidSnapshot snapshot =
                snapshotResult.snapshotValue().orElseThrow();
        Map<StorageFluidKey, Long> reserved = new LinkedHashMap<>();
        for (FluidRequirement requirement : recipeView.fluids()) {
            StorageFluidKey key = ExternalFluidMatcher.find(
                    requirement, snapshot, reserved);
            if (key == null) {
                preparationFailure = Component.literal(
                        "RSI Integration fluid is unavailable: "
                                + requirement.entryId());
                return false;
            }
            ExternalFluidMatcher.reserve(
                    reserved, key, requirement.amount());
            StorageFluidOperationResult simulated =
                    storageEndpoint().session().extractFluidExact(
                            player, key, requirement.amount(), true);
            if (simulated.status() != StorageOperationStatus.SUCCESS
                    || simulated.transferredAmountExact()
                    .orElse(-1L) != requirement.amount()) {
                preparationFailure = Component.literal(
                        "RSI Integration fluid extraction preflight failed: "
                                + requirement.entryId());
                return false;
            }
            StorageFluidOperationResult extracted =
                    storageEndpoint().session().extractFluidExact(
                            player, key, requirement.amount(), false);
            if (extracted.status() != StorageOperationStatus.SUCCESS) {
                refundFluids(player, extracted.transferredFluids());
                preparationFailure = Component.literal(
                        "RSI Integration fluid extraction failed: "
                                + requirement.entryId());
                return false;
            }
            long remaining = requirement.amount();
            for (FluidStack fluid : extracted.transferredFluids()) {
                if (remaining <= 0L) break;
                FluidStack toInsert = fluid.copy();
                toInsert.setAmount((int) Math.min(
                        Integer.MAX_VALUE,
                        Math.min(remaining, toInsert.getAmount())
                ));
                long remainder = insertFluidIntoMachine(
                        requirement, toInsert);
                remaining -= toInsert.getAmount() - remainder;
                if (remainder > 0L) {
                    FluidStack refund = toInsert.copy();
                    refund.setAmount((int) remainder);
                    refundFluids(player, List.of(refund));
                    preparationFailure = Component.literal(
                            "RSI Integration fluid port rejected "
                                    + requirement.entryId());
                    return false;
                }
            }
            if (remaining > 0L) {
                preparationFailure = Component.literal(
                        "RSI Integration fluid extraction was short: "
                                + requirement.entryId());
                return false;
            }
        }
        return true;
    }

    private long insertFluidIntoMachine(
            FluidRequirement requirement,
            FluidStack fluid
    ) {
        if (descriptor == null || fluid.isEmpty()) return fluid.getAmount();
        long remaining = fluid.getAmount();
        for (var port : descriptor.fluidPorts()) {
            if (remaining <= 0L) break;
            if (port.direction() != InputOutputAccess.INPUT
                    || !requirement.inputGroup().equals(port.inputGroup())
                    || port.access() == FluidPortAccess.CUSTOM) {
                continue;
            }
            IFluidHandler handler = fluidHandler(port.side());
            if (handler == null) continue;
            int chunk = (int) Math.min(
                    Integer.MAX_VALUE,
                    Math.min(remaining, port.capacity())
            );
            FluidStack offer = fluid.copy();
            offer.setAmount(chunk);
            int accepted = handler.fill(offer, FluidAction.EXECUTE);
            if (accepted < 0 || accepted > chunk) {
                return remaining;
            }
            remaining -= accepted;
            if (accepted < chunk) break;
        }
        return remaining;
    }

    private void refundFluids(
            ServerPlayer player,
            List<FluidStack> fluids
    ) {
        if (!hasStorageAccess()) return;
        for (FluidStack fluid : fluids) {
            if (fluid == null || fluid.isEmpty()) continue;
            StorageFluidOperationResult refund =
                    storageEndpoint().session().insertFluid(
                            player, fluid.copy(), false);
            if (refund.remainder() == null || refund.remainder().isEmpty()) {
                continue;
            }
            RSIntegrationMod.LOGGER.error(
                    "[RSI-API] failed to return {} mB of {} to storage",
                    refund.remainder().getAmount(),
                    refund.remainder().getFluid()
            );
        }
    }

    private int matchingInputCount(
            MaterialRequirement requirement,
            ItemStack material
    ) {
        if (descriptor == null || material.isEmpty()) return 0;
        var ingredient = ExternalRecipeProjection.ingredient(
                requirement.ingredient());
        int available = 0;
        for (MachineItemPort port : descriptor.itemPorts()) {
            if (port.direction() != InputOutputAccess.INPUT
                    || !requirement.inputGroup().equals(port.inputGroup())) {
                continue;
            }
            ItemStack current = get(port, null);
            if (!current.isEmpty()
                    && ingredient.test(current)) {
                available += current.getCount();
            }
        }
        return available;
    }

    private void removeUninserted(
            ResourceLocation entryId,
            ItemStack material,
            int amount
    ) {
        if (amount <= 0) return;
        int remaining = amount;
        for (int index = 0;
             index < uninsertedMaterials.size() && remaining > 0;
             index++) {
            ItemStack candidate = uninsertedMaterials.get(index);
            if (candidate.isEmpty()
                    || !ItemStack.isSameItemSameTags(candidate, material)) {
                continue;
            }
            int removed = Math.min(remaining, candidate.getCount());
            candidate.shrink(removed);
            remaining -= removed;
            if (candidate.isEmpty()) {
                uninsertedMaterials.remove(index);
                index--;
            }
        }
        if (remaining > 0) {
            RSIntegrationMod.LOGGER.warn(
                    "[RSI-API] unable to subtract {} inserted item(s) from pending recovery for {}",
                    remaining, entryId);
        }
    }

    private void flushPendingInputRefunds(@Nullable ServerPlayer player) {
        for (ItemStack refund : pendingInputRefunds) {
            if (refund.isEmpty()) continue;
            ItemStack remainder = insertIntoStorage(player, refund.copy(), false);
            if (remainder.isEmpty()) continue;
            if (player != null && player.getInventory().add(remainder)) continue;
            if (player != null) {
                player.drop(remainder, false);
            } else if (level != null && machinePos != null) {
                ItemEntity entity = new ItemEntity(
                                level,
                                machinePos.getX() + 0.5D,
                                machinePos.getY() + 1.0D,
                                machinePos.getZ() + 0.5D,
                                remainder
                        );
                level.addFreshEntity(entity);
            }
        }
        pendingInputRefunds.clear();
    }

    private boolean hasOutputCapacity(ServerPlayer player) {
        for (OutputDeclaration output : recipeView.outputs()) {
            if (output.resource().kind()
                    == IntegrationResourceKey.ResourceKind.FLUID) {
                long maximum = output.amount().max();
                FluidStack expected = fluidStack(
                        output.resource(),
                        Math.min(Integer.MAX_VALUE, maximum)
                );
                long capacity = 0L;
                for (MachineFluidPort port : descriptor.fluidPorts()) {
                    if (port.direction() != InputOutputAccess.OUTPUT
                            || !output.outputGroup().equals(
                            port.outputGroup())) {
                        continue;
                    }
                    capacity += fluidCapacityFor(port, expected);
                    if (capacity >= maximum) break;
                }
                if (capacity < maximum) return false;
                continue;
            }
            if (output.resource().kind()
                    != IntegrationResourceKey.ResourceKind.ITEM) {
                continue;
            }
            long maximum = Math.min(Integer.MAX_VALUE, output.amount().max());
            ItemStack expected = ExternalRecipeProjection.toStack(
                    output.resource(), maximum);
            long capacity = 0L;
            for (MachineItemPort port : outputPorts(output.outputGroup())) {
                capacity += capacityFor(port, expected);
                if (capacity >= maximum) break;
            }
            if (capacity < maximum) return false;
        }
        return true;
    }

    private List<MachineItemPort> outputPorts(String outputGroup) {
        return descriptor.itemPorts().stream()
                .filter(port -> port.direction() == InputOutputAccess.OUTPUT)
                .filter(port -> outputGroup.equals(port.outputGroup()))
                .toList();
    }

    private boolean hasNewExpectedOutput() {
        for (OutputDeclaration output : recipeView.outputs()) {
            if (output.resource().kind()
                    != IntegrationResourceKey.ResourceKind.ITEM) {
                continue;
            }
            for (MachineItemPort port : outputPorts(output.outputGroup())) {
                ItemStack current = get(port, null);
                ItemStack before = outputBaseline.get(new SlotKey(port));
                int baseline = before == null ? 0 : before.getCount();
                if (current.getCount() > baseline
                        && matches(current, output)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean hasNewExpectedFluidOutput() {
        for (OutputDeclaration output : recipeView.outputs()) {
            if (output.resource().kind()
                    != IntegrationResourceKey.ResourceKind.FLUID) {
                continue;
            }
            for (MachineFluidPort port : descriptor.fluidPorts()) {
                if (port.direction() != InputOutputAccess.OUTPUT
                        || !output.outputGroup().equals(
                        port.outputGroup())) {
                    continue;
                }
                FluidStack current = getFluid(port);
                FluidStack before = fluidOutputBaseline.get(
                        new FluidSlotKey(port));
                int baseline = before == null ? 0 : before.getAmount();
                if (current.getAmount() > baseline
                        && matchesFluid(current, output.resource())) {
                    return true;
                }
            }
        }
        return false;
    }

    private ItemStack extractMatching(
            MachineItemPort port,
            OutputDeclaration output,
            int maximum,
            ServerPlayer player
    ) {
        ItemStack current = get(port, player);
        if (current.isEmpty() || !matches(current, output)) {
            return ItemStack.EMPTY;
        }
        ItemStack baseline = outputBaseline.get(new SlotKey(port));
        int protectedCount = baseline != null && matches(baseline, output)
                ? baseline.getCount() : 0;
        int available = Math.max(0, current.getCount() - protectedCount);
        return available <= 0
                ? ItemStack.EMPTY
                : extract(port, Math.min(maximum, available), player);
    }

    private boolean matches(ItemStack stack, OutputDeclaration output) {
        IntegrationResourceKey expected = output.resource();
        if (expected.kind() != IntegrationResourceKey.ResourceKind.ITEM
                || !expected.id().equals(BuiltInRegistries.ITEM.getKey(
                stack.getItem()))) {
            return false;
        }
        if (output.dynamicIdentity()) return true;
        return expected.identity().equals(
                stack.getTag() == null ? new CompoundTag() : stack.getTag());
    }

    private void snapshotSlots(
            Map<SlotKey, ItemStack> target,
            List<MachineItemPort> ports
    ) {
        target.clear();
        for (MachineItemPort port : ports) {
            target.put(new SlotKey(port), get(port, null));
        }
    }

    private ItemStack get(MachineItemPort port, @Nullable ServerPlayer player) {
        if (port.access() == ItemPortAccess.CONTAINER) {
            Container container = container();
            return container == null ? ItemStack.EMPTY
                    : container.getItem(port.slot()).copy();
        }
        if (port.access() == ItemPortAccess.ITEM_HANDLER) {
            IItemHandler handler = itemHandler(port.side());
            return handler == null ? ItemStack.EMPTY
                    : handler.getStackInSlot(port.slot()).copy();
        }
        return ItemStack.EMPTY;
    }

    private int count(MachineItemPort port, @Nullable ServerPlayer player) {
        return get(port, player).getCount();
    }

    private ItemStack insert(
            MachineItemPort port,
            ItemStack stack,
            @Nullable ServerPlayer player
    ) {
        return insert(port, stack, false, player);
    }

    private long capacityFor(MachineItemPort port, ItemStack expected) {
        if (port.access() == ItemPortAccess.CONTAINER) {
            Container container = container();
            if (container == null) return 0L;
            ItemStack existing = container.getItem(port.slot());
            if (!existing.isEmpty()
                    && !ItemStack.isSameItemSameTags(existing, expected)) {
                return 0L;
            }
            int limit = Math.min(expected.getMaxStackSize(),
                    Math.min(container.getMaxStackSize(), port.capacity()));
            return Math.max(0, limit - existing.getCount());
        }
        if (port.access() == ItemPortAccess.ITEM_HANDLER) {
            IItemHandler handler = itemHandler(port.side());
            if (handler == null) return 0L;
            ItemStack current = handler.getStackInSlot(port.slot());
            if (!current.isEmpty()
                    && !ItemStack.isSameItemSameTags(current, expected)) {
                return 0L;
            }
            int limit = Math.min(expected.getMaxStackSize(),
                    Math.min(handler.getSlotLimit(port.slot()), port.capacity()));
            int free = Math.max(0, limit - current.getCount());
            if (free == 0) return 0L;
            ItemStack probe = expected.copyWithCount(free);
            ItemStack remainder = handler.insertItem(
                    port.slot(), probe, true);
            return free - remainder.getCount();
        }
        return 0L;
    }

    private long fluidCapacityFor(
            MachineFluidPort port,
            FluidStack expected
    ) {
        if (expected.isEmpty()
                || port.access() == FluidPortAccess.CUSTOM) {
            return 0L;
        }
        IFluidHandler handler = fluidHandler(port.side());
        if (handler == null) return 0L;
        int chunk = (int) Math.min(
                Integer.MAX_VALUE,
                Math.min(port.capacity(), expected.getAmount())
        );
        FluidStack probe = expected.copy();
        probe.setAmount(chunk);
        int accepted = handler.fill(probe, FluidAction.SIMULATE);
        return Math.max(0, Math.min(chunk, accepted));
    }

    private ItemStack insert(
            MachineItemPort port,
            ItemStack stack,
            boolean simulate,
            @Nullable ServerPlayer player
    ) {
        ItemStack limited = stack.copy();
        limited.setCount(Math.min(limited.getCount(), port.capacity()));
        if (port.access() == ItemPortAccess.CONTAINER) {
            Container container = container();
            if (container == null) return stack;
            ItemStack existing = container.getItem(port.slot());
            int limit = Math.min(limited.getMaxStackSize(),
                    Math.min(container.getMaxStackSize(), port.capacity()));
            if (!existing.isEmpty()
                    && !ItemStack.isSameItemSameTags(existing, limited)) {
                return stack;
            }
            int accepted = Math.min(limited.getCount(), limit - existing.getCount());
            if (accepted <= 0 && !existing.isEmpty()) return stack;
            if (!simulate) {
                if (existing.isEmpty()) {
                    ItemStack placed = limited.copyWithCount(accepted);
                    container.setItem(port.slot(), placed);
                } else {
                    existing.grow(accepted);
                    container.setItem(port.slot(), existing);
                }
                container.setChanged();
            }
            ItemStack remainder = stack.copy();
            remainder.shrink(accepted);
            return remainder;
        }
        if (port.access() == ItemPortAccess.ITEM_HANDLER) {
            IItemHandler handler = itemHandler(port.side());
            if (handler == null) return stack;
            return handler.insertItem(port.slot(), stack.copy(), simulate);
        }
        return stack;
    }

    private ItemStack extract(
            MachineItemPort port,
            int amount,
            @Nullable ServerPlayer player
    ) {
        if (port.access() == ItemPortAccess.CONTAINER) {
            Container container = container();
            if (container == null) return ItemStack.EMPTY;
            ItemStack existing = container.getItem(port.slot());
            if (existing.isEmpty()) return ItemStack.EMPTY;
            int extracted = Math.min(amount, existing.getCount());
            ItemStack result = existing.copyWithCount(extracted);
            existing.shrink(extracted);
            container.setItem(port.slot(),
                    existing.isEmpty() ? ItemStack.EMPTY : existing);
            container.setChanged();
            return result;
        }
        if (port.access() == ItemPortAccess.ITEM_HANDLER) {
            IItemHandler handler = itemHandler(port.side());
            return handler == null ? ItemStack.EMPTY
                    : handler.extractItem(port.slot(), amount, false);
        }
        return ItemStack.EMPTY;
    }

    @Nullable
    private Container container() {
        BlockEntity blockEntity = blockEntity();
        return blockEntity instanceof Container container ? container : null;
    }

    @Nullable
    private IItemHandler itemHandler(@Nullable Direction side) {
        BlockEntity blockEntity = blockEntity();
        if (blockEntity == null) return null;
        return blockEntity.getCapability(
                ForgeCapabilities.ITEM_HANDLER, side).orElse(null);
    }

    @Nullable
    private IFluidHandler fluidHandler(@Nullable Direction side) {
        BlockEntity blockEntity = blockEntity();
        if (blockEntity == null) return null;
        return blockEntity.getCapability(
                ForgeCapabilities.FLUID_HANDLER, side).orElse(null);
    }

    private void snapshotFluidSlots(
            Map<FluidSlotKey, FluidStack> target,
            List<MachineFluidPort> ports
    ) {
        target.clear();
        for (var port : ports) {
            target.put(new FluidSlotKey(port), getFluid(port).copy());
        }
    }

    private FluidStack getFluid(
            MachineFluidPort port
    ) {
        IFluidHandler handler = fluidHandler(port.side());
        return handler == null
                ? FluidStack.EMPTY
                : handler.getFluidInTank(port.tank()).copy();
    }

    private FluidStack extractFluid(
            MachineFluidPort port,
            int amount
    ) {
        IFluidHandler handler = fluidHandler(port.side());
        return handler == null
                ? FluidStack.EMPTY
                : handler.drain(amount, FluidAction.EXECUTE);
    }

    private static FluidStack fluidStack(
            IntegrationResourceKey resource,
            long amount
    ) {
        if (resource.kind() != IntegrationResourceKey.ResourceKind.FLUID
                || amount <= 0L) {
            return FluidStack.EMPTY;
        }
        var fluid = ForgeRegistries.FLUIDS.getValue(resource.id());
        if (fluid == null) return FluidStack.EMPTY;
        FluidStack stack = new FluidStack(
                fluid, (int) Math.min(Integer.MAX_VALUE, amount));
        if (!resource.identity().isEmpty()) {
            stack.setTag(resource.identity());
        }
        return stack;
    }

    @Nullable
    private BlockEntity blockEntity() {
        if (level == null || machinePos == null) return null;
        return level.getBlockEntity(machinePos);
    }

    private List<IntegrationDiagnosticMessage> validatePorts(
            RecipeView view,
            MachineDescriptor descriptor
    ) {
        List<IntegrationDiagnosticMessage> failures = new ArrayList<>();
        for (MaterialRequirement material : view.materials()) {
            boolean matched = descriptor.itemPorts().stream().anyMatch(port ->
                    port.direction() == InputOutputAccess.INPUT
                            && material.inputGroup().equals(port.inputGroup())
                            && port.access() != ItemPortAccess.CUSTOM);
            if (!matched) {
                failures.add(new IntegrationDiagnosticMessage(
                        "no item input port for group " + material.inputGroup()));
            }
        }
        for (OutputDeclaration output : view.outputs()) {
            if (output.resource().kind()
                    != IntegrationResourceKey.ResourceKind.ITEM) {
                continue;
            }
            boolean matched = descriptor.itemPorts().stream().anyMatch(port ->
                    port.direction() == InputOutputAccess.OUTPUT
                            && output.outputGroup().equals(port.outputGroup())
                            && port.access() != ItemPortAccess.CUSTOM);
            if (!matched) {
                failures.add(new IntegrationDiagnosticMessage(
                        "no item output port for group " + output.outputGroup()));
            }
        }
        for (FluidRequirement fluid : view.fluids()) {
            boolean matched = descriptor.fluidPorts().stream().anyMatch(port ->
                    port.direction() == InputOutputAccess.INPUT
                            && fluid.inputGroup().equals(port.inputGroup())
                            && port.access() != FluidPortAccess.CUSTOM);
            if (!matched) {
                failures.add(new IntegrationDiagnosticMessage(
                        "no fluid input port for group "
                                + fluid.inputGroup()));
            }
        }
        for (OutputDeclaration output : view.outputs()) {
            if (output.resource().kind()
                    != IntegrationResourceKey.ResourceKind.FLUID) {
                continue;
            }
            boolean matched = descriptor.fluidPorts().stream().anyMatch(port ->
                    port.direction() == InputOutputAccess.OUTPUT
                            && output.outputGroup().equals(port.outputGroup())
                            && port.access() != FluidPortAccess.CUSTOM);
            if (!matched) {
                failures.add(new IntegrationDiagnosticMessage(
                        "no fluid output port for group "
                                + output.outputGroup()));
            }
        }
        return List.copyOf(failures);
    }

    private OutputProbability probability(OutputDeclaration output) {
        OutputProbabilityMode mode = output.probability().mode();
        return switch (mode) {
            case GUARANTEED -> OutputProbability.guaranteed();
            case PROBABILISTIC -> OutputProbability.probabilistic(
                    output.probability().chance(),
                    output.probability().maxAttempts(),
                    output.probability().rollGroup());
            case DYNAMIC -> OutputProbability.dynamic(
                    output.probability().chance(),
                    output.probability().maxAttempts(),
                    output.probability().rollGroup());
        };
    }

    private Component firstDiagnostic(
            List<IntegrationDiagnostic> diagnostics,
            String fallback
    ) {
        return Component.literal(diagnostics.isEmpty()
                ? fallback : diagnostics.get(0).code().name());
    }

    private static void merge(
            Map<ItemStackKey, ItemStack> target,
            ItemStack stack
    ) {
        ItemStackKey key = ItemStackKey.of(stack);
        ItemStack existing = target.get(key);
        if (existing == null) {
            target.put(key, stack.copy());
        } else {
            existing.grow(stack.getCount());
        }
    }

    private record SlotKey(ItemPortAccess access, int slot, @Nullable Direction side) {
        SlotKey(MachineItemPort port) {
            this(port.access(), port.slot(), port.side());
        }
    }

    private record FluidSlotKey(
            FluidPortAccess access,
            int tank,
            @Nullable Direction side
    ) {
        FluidSlotKey(MachineFluidPort port) {
            this(port.access(), port.tank(), port.side());
        }
    }

    private record ItemStackKey(
            ResourceLocation itemId,
            @Nullable CompoundTag tag,
            @Nullable CompoundTag capabilities
    ) {
        static ItemStackKey of(ItemStack stack) {
            CompoundTag copy = stack.getTag() == null
                    ? null : stack.getTag().copy();
            CompoundTag capabilities = null;
            if (copy != null && copy.contains("ForgeCaps")) {
                capabilities = copy.getCompound("ForgeCaps").copy();
            }
            return new ItemStackKey(
                    BuiltInRegistries.ITEM.getKey(stack.getItem()),
                    copy,
                    capabilities
            );
        }
    }

    private record InsertedMaterial(
            ResourceLocation entryId,
            SlotKey slot,
            ItemStackKey item,
            int count
    ) {}

    private record IntegrationDiagnosticMessage(String message) {}

    private record ReservedMaterials(
            List<ReservedMaterial> materials
    ) implements ReservedMaterialView {
        private ReservedMaterials {
            materials = materials == null ? List.of() : List.copyOf(materials);
        }

        @Override
        public Optional<ReservedMaterial> find(String entryId) {
            return materials.stream()
                    .filter(material -> material.entryId().equals(entryId))
                    .findFirst();
        }
    }
}
