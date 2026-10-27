package com.huanghuang.rsintegration.crafting.external;

import com.huanghuang.rsintegration.api.IntegrationResourceKey;
import com.huanghuang.rsintegration.api.RSIIntegrationApi;
import com.huanghuang.rsintegration.api.diagnostic.IntegrationDiagnostic;
import com.huanghuang.rsintegration.api.diagnostic.IntegrationDiagnosticCode;
import com.huanghuang.rsintegration.api.diagnostic.IntegrationSeverity;
import com.huanghuang.rsintegration.api.execution.CollectedOutput;
import com.huanghuang.rsintegration.api.execution.CancelReason;
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
import com.huanghuang.rsintegration.api.execution.OutputPort;
import com.huanghuang.rsintegration.api.execution.OperationSnapshot;
import com.huanghuang.rsintegration.api.execution.RecipeExecutionIntegration;
import com.huanghuang.rsintegration.api.execution.ReservedMaterial;
import com.huanghuang.rsintegration.api.execution.ReservedMaterialView;
import com.huanghuang.rsintegration.api.execution.RunningOperation;
import com.huanghuang.rsintegration.api.machine.InputOutputAccess;
import com.huanghuang.rsintegration.api.machine.ItemPortAccess;
import com.huanghuang.rsintegration.api.machine.FluidPortAccess;
import com.huanghuang.rsintegration.api.machine.ConcurrencyCapabilities;
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
import com.huanghuang.rsintegration.api.machine.SupportScope;
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
import com.huanghuang.rsintegration.crafting.MaterialMatcher;
import com.huanghuang.rsintegration.crafting.batch.AbstractBatchDelegate;
import com.huanghuang.rsintegration.crafting.batch.BatchConcurrencyCapabilities;
import com.huanghuang.rsintegration.crafting.batch.OperationStartContext;
import com.huanghuang.rsintegration.crafting.batch.OutputAccounting;
import com.huanghuang.rsintegration.crafting.batch.OutputContract;
import com.huanghuang.rsintegration.crafting.batch.ParallelBatchSizing;
import com.huanghuang.rsintegration.crafting.graph.MaterialKey;
import com.huanghuang.rsintegration.crafting.graph.OutputProbability;
import com.huanghuang.rsintegration.feature.unifiedgrid.UnifiedGridFluidRecovery;
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
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
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
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 公共 Integration API 的图节点执行适配器。
 *
 * <p>物品标准 I/O 在 RSI 主线程完成端口容量检查、投料、状态观察和
 * 真实产物收取。无法无损落入标准物品端口的声明在这里显式拒绝。</p>
 */
public final class ExternalIntegrationBatchDelegate extends AbstractBatchDelegate {
    private static final Logger LOGGER = LogManager.getLogger("RS Integration");
    private final Supplier<RSIIntegrationApi> apiProvider;
    private final boolean retainChunks;
    private ServerLevel level;
    private BlockPos machinePos;
    private Recipe<?> recipe;
    private RecipeIntegration<?> recipeIntegration;
    private RecipeView recipeView;
    private MachineIntegration machineIntegration;
    private MachineContext machineContext;
    private MachineDescriptor descriptor;
    private MachineStateProvider stateProvider;
    private RecipeExecutionKind executionKind;
    private RecipeExecutionIntegration executionIntegration;
    private ExecutionPlan executionPlan;
    @Nullable
    private IntegrationResourceKey targetResource;
    private RunningOperation runningOperation;
    private OperationStartContext operationStartContext;
    private ReservedMaterialView customMaterials;
    private final Map<String, List<FluidStack>> customReservedFluids =
            new LinkedHashMap<>();
    private final Map<ResourceLocation, Long> customCollectedOutputTotals =
            new LinkedHashMap<>();
    private final Map<String, Long> customCollectedMaterialTotals =
            new LinkedHashMap<>();
    private final List<ItemStack> customCollectedItems = new ArrayList<>();
    private final List<CollectedOutput> customCollectedOutputs =
            new ArrayList<>();
    private final List<MaterialDisposition> customCollectedDispositions =
            new ArrayList<>();
    private MachineContext customMachineContext;
    @Nullable
    private UUID customOwnerId;
    private boolean customStartPending;
    private boolean customPendingRefundSafe;
    @Nullable
    private CancelReason customCancelReason;
    @Nullable
    private OperationSnapshot customSnapshot;
    private long lastCustomSnapshotSequence = -1L;
    private long lastCustomStateRevision = -1L;
    private long lastCollectionSequence;
    private boolean customCollectionComplete;
    private long recipeRevision;
    private boolean operationStarted;
    private boolean resultsCollected;
    private long lastStateRevision = -1L;
    private long startedAtTick;
    private int plannedBatchOperations = 1;
    @Nullable
    private Component preparationFailure;
    private boolean preparationRetryable;
    private final Map<SlotKey, ItemStack> inputBaseline = new LinkedHashMap<>();
    private final Map<SlotKey, ItemStack> outputBaseline = new LinkedHashMap<>();
    private final Map<FluidSlotKey, FluidStack> fluidInputBaseline =
            new LinkedHashMap<>();
    private final Map<FluidSlotKey, FluidStack> fluidOutputBaseline =
            new LinkedHashMap<>();
    private final List<CollectedOutput> reportedResources =
            new ArrayList<>();
    @Nullable
    private String reportedResourceFailure;
    private final List<InsertedMaterial> insertedMaterials = new ArrayList<>();
    private final List<ItemStack> uninsertedMaterials = new ArrayList<>();
    private final List<ItemStack> pendingInputRefunds = new ArrayList<>();

    public ExternalIntegrationBatchDelegate() {
        this(() -> RSIIntegrationApi.get().orElse(null), true);
    }

    @Override
    public void setTargetOutput(@Nullable ItemStack target) {
        super.setTargetOutput(target);
        targetResource = target == null || target.isEmpty()
                ? null
                : IntegrationResourceKey.item(target);
    }

    @Override
    public void setTargetResource(
            @Nullable IntegrationResourceKey target
    ) {
        targetResource = target;
        super.setTargetOutput(
                target != null
                        && target.kind()
                        == IntegrationResourceKey.ResourceKind.ITEM
                        ? ExternalRecipeProjection.toStack(target, 1L)
                        : null
        );
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
    public void prepareGraphBatch(int executions) {
        plannedBatchOperations = Math.max(1, executions);
    }

    @Override
    @Nullable
    public String lastStartFailureDetail() {
        return preparationFailure == null
                ? null : preparationFailure.getString();
    }

    @Override
    public int prepareFlatBatch(int remainingOperations) {
        int requested = Math.max(0, remainingOperations);
        if (requested <= 1) {
            plannedBatchOperations = 1;
            return requested;
        }
        int limit = batchOperationLimit(recipeView, descriptor);
        int batch = limit <= 1 ? 1 : Math.min(requested, limit);
        plannedBatchOperations = Math.max(1, batch);
        return batch;
    }

    @Override
    public int flatBatchOperationLimit(int configuredLimit) {
        int limit = batchOperationLimit(recipeView, descriptor);
        return limit <= 1
                ? Math.max(1, configuredLimit)
                : Math.max(Math.max(1, configuredLimit), limit);
    }

    @Override
    public boolean expandsFlatBatchOperationLimit() {
        return batchOperationLimit(recipeView, descriptor) > 1;
    }

    @Override
    public int preferredParallelBatchSize(
            int totalOperations,
            int workerCount
    ) {
        int limit = batchOperationLimit(recipeView, descriptor);
        if (limit <= 1 || totalOperations <= 1) return 1;
        return ParallelBatchSizing.boundedEvenShare(
                totalOperations, workerCount, limit);
    }

    @Override
    protected boolean startOperationImpl(OperationStartContext context) {
        this.operationStartContext = context;
        return super.startOperationImpl(context);
    }

    @Override
    public PreparationResult prepare(
            ServerPlayer player,
            ResourceLocation recipeId,
            @Nullable ResourceLocation dim,
            BlockPos pos
    ) {
        preparationFailure = null;
        preparationRetryable = false;
        if (validateAndInit(player, recipeId, dim, pos)) {
            return PreparationResult.ready();
        }
        Component message = preparationFailure != null
                ? preparationFailure
                : Component.literal("RSI Integration machine is not ready");
        return preparationRetryable
                ? PreparationResult.waiting(
                "integration_machine_preparation_failed", message)
                : PreparationResult.fatal(
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
            preparationRetryable = true;
            preparationFailure = Component.literal(
                    "RSI Integration API is unavailable");
            return false;
        }
        level = CraftPacketUtils.resolveLevel(player.server, dim, player);
        if (level == null || !level.hasChunkAt(pos)) {
            preparationRetryable = true;
            preparationFailure = Component.literal(
                    "RSI Integration machine chunk is not loaded");
            return false;
        }
        machinePos = pos.immutable();
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
        this.machineIntegration = machine;
        this.machineContext = machineContext;
        MachineDescriptorResult described;
        try {
            described = machine.describe(machineContext, blockEntity);
        } catch (RuntimeException | LinkageError failure) {
            preparationRetryable = true;
            preparationFailure = Component.literal(
                    "RSI Integration machine describe failed: "
                            + failure.getClass().getSimpleName());
            return false;
        }
        if (described.descriptor() == null) {
            preparationRetryable = true;
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
        MachineStateProvider provider = machine.stateProvider().orElse(null);
        if (!customRecipe && provider != null) {
            MachineState state;
            try {
                state = provider.readState(machineContext, blockEntity);
            } catch (RuntimeException | LinkageError failure) {
                preparationRetryable = true;
                preparationFailure = Component.literal(
                        "RSI Integration machine state read failed: "
                                + failure.getClass().getSimpleName());
                return false;
            }
            if (!state.loaded() || !state.valid()) {
                preparationRetryable = true;
                preparationFailure = Component.literal(
                        "RSI Integration machine is unloaded or invalid");
                return false;
            }
            if (state.failure() != null) {
                preparationRetryable = true;
                preparationFailure = Component.literal(
                        "RSI Integration machine reported "
                                + state.failure().code());
                return false;
            }
            if (state.busy()) {
                preparationRetryable = true;
                preparationFailure = Component.literal(
                        "RSI Integration machine is busy with recipe "
                                + state.activeRecipeId()
                                + " and process "
                                + state.activeProcessId());
                return false;
            }
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
            try {
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
                                targetResource
                        )
                );
                if (!result.executionAvailable()) {
                    preparationRetryable = true;
                    preparationFailure = firstDiagnostic(
                            result.diagnostics(),
                            "RSI Integration recipe is temporarily unavailable");
                    return false;
                }
            } catch (RuntimeException | LinkageError failure) {
                preparationRetryable = true;
                preparationFailure = Component.literal(
                        "RSI Integration availability callback failed: "
                                + failure.getClass().getSimpleName());
                return false;
            }
        }
        this.machinePos = pos.immutable();
        this.recipe = found;
        this.recipeIntegration = recipeClaim.winner();
        this.recipeView = projection.view();
        this.plannedBatchOperations = 1;
        this.descriptor = machineDescriptor;
        this.stateProvider = provider;
        this.executionKind = executionKind;
        this.executionIntegration = custom;
        this.executionPlan = null;
        this.runningOperation = null;
        this.operationStartContext = null;
        this.customMaterials = null;
        this.customReservedFluids.clear();
        this.customOwnerId = null;
        this.customMachineContext = null;
        this.customStartPending = false;
        this.customPendingRefundSafe = false;
        this.customCancelReason = null;
        this.customSnapshot = null;
        this.lastCustomSnapshotSequence = -1L;
        this.lastCustomStateRevision = -1L;
        resetCustomCollectionState();
        this.lastCollectionSequence = 0L;
        this.recipeRevision = projection.revision();
        this.operationStarted = false;
        this.resultsCollected = false;
        this.lastStateRevision = -1L;
        this.inputBaseline.clear();
        this.outputBaseline.clear();
        this.fluidInputBaseline.clear();
        this.fluidOutputBaseline.clear();
        this.reportedResources.clear();
        this.reportedResourceFailure = null;
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
            return failPreparedStart("execution context is invalid");
        }
        if (executionKind == RecipeExecutionKind.CUSTOM) {
            boolean started = startCustomOperation(
                    player, materials, sharedLedger);
            if (!started) logPreparedStartFailure();
            return started;
        }
        this.ledger = sharedLedger;
        this.operationStarted = false;
        this.resultsCollected = false;
        this.reportedResources.clear();
        this.reportedResourceFailure = null;
        this.startedAtTick = level.getGameTime();
        this.insertedMaterials.clear();
        this.uninsertedMaterials.clear();
        this.pendingInputRefunds.clear();
        for (ItemStack stack : materials) {
            if (!stack.isEmpty()) uninsertedMaterials.add(stack.copy());
        }
        if (materials.size() < recipeView.materials().size()) {
            return failPreparedStart(
                    "prepared material list is incomplete");
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
        Map<SlotKey, Integer> consumedBaselineInputs =
                new LinkedHashMap<>();
        if (!hasOutputCapacity(player)) {
            return failPreparedStart(
                    "machine output ports have insufficient capacity");
        }
        for (int index = 0; index < recipeView.materials().size(); index++) {
            MaterialRequirement requirement = recipeView.materials().get(index);
            ItemStack stack = materials.get(index).copy();
            if (stack.isEmpty()) {
                return failPreparedStart(
                        "prepared material is empty: "
                                + requirement.entryId());
            }
            int existing = takeMatchingInputCount(
                    requirement,
                    stack,
                    consumedBaselineInputs
            );
            if (existing > 0) {
                int used = Math.min(existing, stack.getCount());
                pendingInputRefunds.add(stack.copyWithCount(used));
                stack.shrink(used);
            }
            if (stack.isEmpty()) continue;
            ItemStack remainder = insertMaterial(
                    player, requirement, stack);
            if (!remainder.isEmpty()) {
                return failPreparedStart(
                        "input port rejected "
                                + requirement.entryId());
            }
            int inserted = stack.getCount() - remainder.getCount();
            removeUninserted(requirement.entryId(), stack, inserted);
        }
        if (!insertFluidInputs(player)) {
            return logPreparedStartFailure();
        }
        markCraftStarted();
        this.operationStarted = true;
        retainMachineChunk();
        return true;
    }

    private boolean failPreparedStart(String detail) {
        preparationFailure = Component.literal(
                "RSI Integration " + detail);
        return logPreparedStartFailure();
    }

    private boolean logPreparedStartFailure() {
        LOGGER.warn(
                "[RSI-API] start rejected for {} at {}: {}",
                recipe == null ? "unknown" : recipe.getId(),
                machinePos,
                preparationFailure == null
                        ? "unknown preparation failure"
                        : preparationFailure.getString()
        );
        return false;
    }

    @Override
    protected CraftObservation observeMachineCraft(
            ServerLevel level,
            BlockEntity blockEntity
    ) {
        if (!operationStarted) return workingObservation();
        String descriptorFailure = validateCurrentDescriptor();
        if (descriptorFailure != null) {
            return failObservation(descriptorFailure);
        }
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
            } catch (RuntimeException | LinkageError exception) {
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
            long requested = plannedOutputMaximum(output);
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
    public List<OutputAccounting.CollectedOutput> collectStructuredResults(
            ServerPlayer player
    ) {
        if (resultsCollected || recipeView == null) return List.of();
        if (executionKind == RecipeExecutionKind.CUSTOM) {
            if (!customCollectionComplete) return List.of();
            List<OutputAccounting.CollectedOutput> collected =
                    new ArrayList<>();
            for (CollectedOutput output
                    : customCollectedOutputs) {
                if (output.resource().kind()
                        != IntegrationResourceKey.ResourceKind.ITEM) {
                    continue;
                }
                appendStructuredItemOutput(
                        collected,
                        output.portId(),
                        output.resource(),
                        output.amount()
                );
            }
            resultsCollected = true;
            return List.copyOf(collected);
        }

        List<OutputAccounting.CollectedOutput> collected =
                new ArrayList<>();
        for (OutputDeclaration output : recipeView.outputs()) {
            if (output.resource().kind()
                    != IntegrationResourceKey.ResourceKind.ITEM) {
                continue;
            }
            long remaining = Math.min(
                    Integer.MAX_VALUE, plannedOutputMaximum(output));
            for (MachineItemPort port : outputPorts(
                    output.outputGroup())) {
                if (remaining <= 0L) break;
                ItemStack actual = extractMatching(
                        port,
                        output,
                        (int) Math.min(
                                Integer.MAX_VALUE, remaining),
                        player
                );
                if (actual.isEmpty()) continue;
                collected.add(new OutputAccounting.CollectedOutput(
                        output.portId().toString(),
                        OutputContract.Source.SLOT,
                        actual
                ));
                remaining -= actual.getCount();
            }
        }
        resultsCollected = true;
        return List.copyOf(collected);
    }

    private void appendStructuredItemOutput(
            List<OutputAccounting.CollectedOutput> target,
            ResourceLocation portId,
            IntegrationResourceKey resource,
            long amount
    ) {
        long remaining = amount;
        while (remaining > 0L) {
            int chunk = (int) Math.min(
                    Integer.MAX_VALUE, remaining);
            target.add(new OutputAccounting.CollectedOutput(
                    portId.toString(),
                    OutputContract.Source.SLOT,
                    ExternalRecipeProjection.toStack(resource, chunk)
            ));
            remaining -= chunk;
        }
    }

    @Override
    public List<CollectedOutput> drainReportedResources() {
        if (reportedResources.isEmpty()) return List.of();
        List<CollectedOutput> result = List.copyOf(reportedResources);
        reportedResources.clear();
        return result;
    }

    @Override
    public void prepareCancellation(
            @Nullable ServerPlayer player,
            CancelReason reason
    ) {
        this.customCancelReason = reason;
    }

    @Override
    public String reportedResourceFailure() {
        return reportedResourceFailure;
    }

    @Override
    public boolean collectsPhysicalSecondaryOutputs() {
        return true;
    }

    @Override
    public OutputContract outputContract() {
        if (recipeView == null) return OutputContract.none();
        if (recipeView.outputs().stream().anyMatch(output ->
                output.resource().kind()
                        == IntegrationResourceKey.ResourceKind.FLUID)) {
            // 流体不能进入物品 OutputContract；混合输出统一走 legacy collector，
            // 让物品与流体在同一次收集里按真实结果结算。
            return OutputContract.none();
        }
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
    @Nullable
    public BatchConcurrencyCapabilities concurrencyCapabilities() {
        if (descriptor == null) return null;
        ConcurrencyCapabilities source = descriptor.concurrency();
        return new BatchConcurrencyCapabilities(
                materials(source.materials()),
                outputOwnership(source.outputOwnership()),
                cleanupContract(source.cleanup()),
                sideEffects(source.sideEffects()),
                preparationContract(source.preparation()),
                supportOffsets(descriptor.supportScope())
        );
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
            try {
                runningOperation.onSettled(player);
            } catch (RuntimeException | LinkageError failure) {
                LOGGER.warn(
                        "[RSI-API] custom onSettled failed for {}",
                        recipe == null ? "unknown" : recipe.getId(),
                        failure
                );
            } finally {
                runningOperation = null;
            }
        }
        if (executionKind == RecipeExecutionKind.CUSTOM) {
            persistCustomOperation(
                    ExternalOperationJournal.State.COMPLETED,
                    "settled");
        }
        flushPendingInputRefunds(player);
        if (!customReservedFluids.isEmpty()) {
            retainCustomFluids(
                    Map.copyOf(customReservedFluids), player);
            customReservedFluids.clear();
        }
        customOwnerId = null;
        customCancelReason = null;
        customSnapshot = null;
        lastCustomSnapshotSequence = -1L;
        resetCustomCollectionState();
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
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug(
                    "[RSI-API] custom start recipe={} expectedEntries={} materialEntries={} counts={}",
                    recipe == null ? "unknown" : recipe.getId(),
                    recipeView.materials().size(),
                    materials.size(),
                    materials.stream().map(ItemStack::getCount).toList()
            );
        }
        if (operationStartContext == null
                || operationStartContext.transactionId() == null
                || operationStartContext.operationId() == null) {
            preparationFailure = Component.literal(
                    "RSI Integration custom operation identity is missing");
            return false;
        }
        this.ledger = sharedLedger;
        this.customOwnerId = player.getUUID();
        this.customReservedFluids.clear();
        this.customCancelReason = null;
        this.customSnapshot = null;
        this.lastCustomSnapshotSequence = -1L;
        resetCustomCollectionState();
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
        if (!reserveCustomFluids(player, reserved)) {
            return false;
        }
        ReservedMaterialView materialView = new ReservedMaterials(reserved);
        MachineContext machineContext = new MachineContext(
                machineIntegration.id(), level, machinePos, player);
        this.customMaterials = materialView;
        this.customMachineContext = machineContext;
        RecipeContext recipeContext = new RecipeContext(
                recipeIntegration.id(), level.registryAccess(), recipeRevision);
        ExecutionPlanResult planned;
        try {
            planned = executionIntegration.createPlan(
                    new ExecutionRequestContext(
                            recipeContext,
                            recipe,
                            recipeView,
                            machineContext,
                            materialView,
                            operationStartContext.transactionId(),
                            operationStartContext.operationId(),
                            operationStartContext.attempt(),
                            targetResource
                    )
            );
        } catch (RuntimeException | LinkageError failure) {
            preparationFailure = Component.literal(
                    "RSI custom execution createPlan failed: "
                            + failure.getClass().getSimpleName());
            return false;
        }
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
        persistCustomOperation(
                ExternalOperationJournal.State.PRE_START,
                "starting");
        ExecutionResult started = startCustomIntegration(player);
        if (started.status() == ExecutionStartStatus.RETRY) {
            this.customStartPending = true;
            this.startedAtTick = level.getGameTime();
            markCraftStarted();
            this.operationStarted = true;
            retainMachineChunk();
            persistCustomOperation(
                    ExternalOperationJournal.State.PRE_START,
                    "start retry");
            return true;
        }
        if (started.status() != ExecutionStartStatus.STARTED
                || started.operation() == null) {
            preparationFailure = firstDiagnostic(
                    started.diagnostics(),
                    "RSI custom execution rejected start");
            persistCustomOperation(
                    ExternalOperationJournal.State.FAILED,
                    "start rejected");
            return false;
        }
        this.runningOperation = started.operation();
        this.customStartPending = false;
        this.startedAtTick = level.getGameTime();
        markCraftStarted();
        this.operationStarted = true;
        retainMachineChunk();
        captureCustomSnapshot();
        persistCustomOperation(
                ExternalOperationJournal.State.ACTIVE,
                "started");
        return true;
    }

    private ExecutionResult startCustomIntegration(ServerPlayer player) {
        try {
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
        } catch (RuntimeException | LinkageError failure) {
            return ExecutionResult.rejected(new IntegrationDiagnostic(
                    IntegrationDiagnosticCode.EXECUTION_START_REJECTED,
                    IntegrationSeverity.ERROR,
                    executionIntegration.id(),
                    Map.of("failure", failure.getClass().getName())
            ));
        }
    }

    @Nullable
    private com.huanghuang.rsintegration.api.execution.CraftObservation
    observeRunningOperation(ServerLevel level) {
        try {
            return runningOperation.observe(level);
        } catch (RuntimeException | LinkageError failure) {
            LOGGER.warn(
                    "[RSI-API] custom observation failed for {}",
                    recipe == null ? "unknown" : recipe.getId(),
                    failure
            );
            return null;
        }
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
        return validateCustomPlanAgainstRecipe(
                recipeView, customMaterials, plan);
    }

    static String validateCustomPlanAgainstRecipe(
            RecipeView recipeView,
            ReservedMaterialView materials,
            ExecutionPlan plan
    ) {
        Map<String, MaterialRequirement> itemRequirements =
                new LinkedHashMap<>();
        for (MaterialRequirement requirement : recipeView.materials()) {
            itemRequirements.put(
                    requirement.entryId().toString(), requirement);
        }
        for (var route : plan.itemInputs()) {
            MaterialRequirement requirement =
                    itemRequirements.get(route.entryId());
            if (requirement == null) {
                return "custom plan contains an unknown item entry: "
                        + route.entryId();
            }
            if (!route.inputGroup().equals(requirement.inputGroup())) {
                return "custom item route group mismatch: "
                        + route.entryId();
            }
            ReservedMaterial reserved =
                    materials.find(route.entryId()).orElse(null);
            if (reserved == null
                    || reserved.resource().kind()
                    != IntegrationResourceKey.ResourceKind.ITEM
                    || route.amount() > reserved.amount()) {
                return "custom item route exceeds its reservation: "
                        + route.entryId();
            }
        }

        Map<String, FluidRequirement> fluidRequirements =
                new LinkedHashMap<>();
        for (FluidRequirement requirement : recipeView.fluids()) {
            fluidRequirements.put(
                    requirement.entryId().toString(), requirement);
        }
        for (var route : plan.fluidInputs()) {
            FluidRequirement requirement =
                    fluidRequirements.get(route.entryId());
            if (requirement == null) {
                return "custom plan contains an unknown fluid entry: "
                        + route.entryId();
            }
            if (!route.inputGroup().equals(requirement.inputGroup())) {
                return "custom fluid route group mismatch: "
                        + route.entryId();
            }
            ReservedMaterial reserved =
                    materials.find(route.entryId()).orElse(null);
            if (reserved == null
                    || reserved.resource().kind()
                    != IntegrationResourceKey.ResourceKind.FLUID
                    || route.amount() > reserved.amount()) {
                return "custom fluid route exceeds its reservation: "
                        + route.entryId();
            }
        }

        Map<ResourceLocation, OutputDeclaration> declaredOutputs =
                new LinkedHashMap<>();
        for (OutputDeclaration output : recipeView.outputs()) {
            declaredOutputs.put(output.portId(), output);
        }
        if (declaredOutputs.size() != plan.outputPorts().size()) {
            return "custom plan output port set does not match the recipe";
        }
        for (var port : plan.outputPorts()) {
            OutputDeclaration declared = declaredOutputs.get(port.portId());
            if (declared == null) {
                return "custom plan contains an unknown output port: "
                        + port.portId();
            }
            if (!Objects.equals(port.outputGroup(), declared.outputGroup())
                    || !Objects.equals(port.resource(), declared.resource())
                    || !Objects.equals(port.amount(), declared.amount())
                    || port.outputKind() != declared.outputKind()
                    || !Objects.equals(port.probability(), declared.probability())
                    || port.dynamicIdentity() != declared.dynamicIdentity()
                    || port.targetProducing() != declared.targetProducing()
                    || !Objects.equals(port.sourceInputEntryId(),
                    declared.sourceInputEntryId())) {
                return "custom output declaration mismatch: "
                        + port.portId();
            }
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
            captureCustomSnapshot();
        }
        if (runningOperation == null) {
            return failObservation("custom operation is missing");
        }
        var observation = observeRunningOperation(level);
        if (observation == null) {
            if (!resumeCustomOperation(level)) {
                return failObservation("custom observation callback failed");
            }
            observation = observeRunningOperation(level);
            if (observation == null) {
                return workingObservation();
            }
        }
        captureCustomSnapshot();
        if (lastCustomStateRevision >= 0L
                && observation.stateRevision() < lastCustomStateRevision) {
            return failObservation("custom state revision moved backwards");
        }
        lastCustomStateRevision = observation.stateRevision();
        return switch (observation.phase()) {
            case QUEUED, PREPARING, WAITING_FOR_MACHINE, WORKING ->
                    workingObservation();
            case READY_TO_COLLECT, COLLECTING -> {
                String failure = collectCustomStep();
                if (failure != null) {
                    yield failObservation(failure);
                }
                captureCustomSnapshot();
                yield customCollectionComplete
                        ? doneObservation() : workingObservation();
            }
            case SETTLING, SUCCEEDED -> customCollectionComplete
                    ? doneObservation()
                    : failObservation(
                    "custom operation left collection before reporting completion");
            case FAILED, CANCELLED ->
                    failObservation("custom operation reported "
                            + observation.phase());
            case CANCELLING ->
                    failObservation("custom operation is cancelling");
        };
    }

    @Nullable
    private String collectCustomStep() {
        if (customCollectionComplete) return null;
        if (runningOperation == null
                || customMaterials == null
                || executionPlan == null
                || operationStartContext == null) {
            return "custom collection context is missing";
        }
        OperationCollection collection;
        try {
            collection = runningOperation.collect(
                    operationStartContext.player());
        } catch (RuntimeException | LinkageError failure) {
            LOGGER.warn(
                    "[RSI-API] custom collect failed for {}",
                    recipe == null ? "unknown" : recipe.getId(),
                    failure
            );
            return "custom collect callback failed";
        }
        if (collection.collectionSequence()
                < lastCollectionSequence) {
            return "custom collection sequence moved backwards";
        }
        if (collection.collectionSequence()
                == lastCollectionSequence) {
            return null;
        }
        String validationFailure = validateCustomCollection(collection);
        if (validationFailure != null) {
            reportedResourceFailure = validationFailure;
            return validationFailure;
        }
        lastCollectionSequence = collection.collectionSequence();
        return null;
    }

    @Nullable
    private String validateCustomCollection(
            OperationCollection collection
    ) {
        Map<ResourceLocation, OutputPort> declaredPorts =
                new LinkedHashMap<>();
        for (OutputPort port : executionPlan.outputPorts()) {
            declaredPorts.put(port.portId(), port);
        }
        Map<ResourceLocation, Long> nextOutputTotals =
                new LinkedHashMap<>(customCollectedOutputTotals);
        Map<String, Long> nextMaterialTotals =
                new LinkedHashMap<>(customCollectedMaterialTotals);
        List<CollectedOutput> acceptedOutputs =
                new ArrayList<>(collection.outputs().size());
        List<MaterialDisposition> acceptedDispositions =
                new ArrayList<>(collection.materials().size());

        for (CollectedOutput output : collection.outputs()) {
            OutputPort declared = declaredPorts.get(output.portId());
            if (declared == null) {
                return "custom collection returned undeclared output "
                        + output.portId();
            }
            long previous = nextOutputTotals.getOrDefault(
                    output.portId(), 0L);
            String failure = validateCollectedOutputAgainstPort(
                    declared, output, previous);
            if (failure != null) return failure;
            long total;
            try {
                total = Math.addExact(previous, output.amount());
            } catch (ArithmeticException exception) {
                return "custom output total overflowed: "
                        + output.portId();
            }
            nextOutputTotals.put(output.portId(), total);
            acceptedOutputs.add(output);
        }

        for (MaterialDisposition disposition : collection.materials()) {
            ReservedMaterial reserved = customMaterials.find(
                    disposition.material().entryId()).orElse(null);
            if (reserved == null) {
                return "custom collection returned an undeclared material: "
                        + disposition.material().entryId();
            }
            long previous = nextMaterialTotals.getOrDefault(
                    reserved.entryId(), 0L);
            String failure = validateMaterialDispositionAgainstReservation(
                    reserved, disposition, previous);
            if (failure != null) return failure;
            long total;
            try {
                total = Math.addExact(
                        previous, disposition.material().amount());
            } catch (ArithmeticException exception) {
                return "custom material disposition overflowed: "
                        + reserved.entryId();
            }
            nextMaterialTotals.put(reserved.entryId(), total);
            acceptedDispositions.add(disposition);
        }

        if (collection.complete()) {
            for (OutputPort port : executionPlan.outputPorts()) {
                long total = nextOutputTotals.getOrDefault(
                        port.portId(), 0L);
                if (port.probability().mode()
                        == OutputProbabilityMode.GUARANTEED
                        && total < port.amount().min()) {
                    return "custom output " + port.portId()
                            + " is below its guaranteed minimum";
                }
            }
            for (ReservedMaterial material : customMaterials.materials()) {
                long total = nextMaterialTotals.getOrDefault(
                        material.entryId(), 0L);
                if (total != material.amount()) {
                    return "custom collection did not account for reserved material "
                            + material.entryId();
                }
            }
            if (acceptedDispositions.stream().anyMatch(disposition ->
                    disposition.outcome() == MaterialOutcome.UNCERTAIN)
                    || customCollectedDispositions.stream().anyMatch(disposition ->
                    disposition.outcome() == MaterialOutcome.UNCERTAIN)) {
                return "completed custom collection contains uncertain materials";
            }
        }

        customCollectedOutputTotals.clear();
        customCollectedOutputTotals.putAll(nextOutputTotals);
        customCollectedMaterialTotals.clear();
        customCollectedMaterialTotals.putAll(nextMaterialTotals);
        customCollectedDispositions.addAll(acceptedDispositions);
        customCollectedOutputs.addAll(acceptedOutputs);
        for (CollectedOutput output : acceptedOutputs) {
            if (output.resource().kind()
                    == IntegrationResourceKey.ResourceKind.ITEM) {
                appendItemOutput(output.resource(), output.amount());
            }
        }
        if (collection.complete()) {
            customCollectionComplete = true;
            List<CollectedOutput> fluidOutputs =
                    customCollectedOutputs.stream()
                            .filter(output -> output.resource().kind()
                                    == IntegrationResourceKey.ResourceKind.FLUID)
                            .toList();
            if (!fluidOutputs.isEmpty()) {
                persistCustomOperation(
                        ExternalOperationJournal.State.DELIVERING,
                        "delivering custom fluid outputs");
            }
            for (CollectedOutput output : fluidOutputs) {
                String deliveryFailure = deliverCustomFluidOutput(
                        operationStartContext.player(), output);
                if (deliveryFailure != null) {
                    return deliveryFailure;
                }
            }
            if (!fluidOutputs.isEmpty()) {
                customCollectedOutputs.removeIf(output ->
                        output.resource().kind()
                                == IntegrationResourceKey.ResourceKind.FLUID);
                persistCustomOperation(
                        ExternalOperationJournal.State.ACTIVE,
                        "fluid outputs delivered");
            }
            settleCustomFluidInputs(
                    customCollectedDispositions,
                    operationStartContext.player());
        }
        return null;
    }

    @Nullable
    private String deliverCustomFluidOutput(
            ServerPlayer player,
            CollectedOutput output
    ) {
        if (!hasStorageAccess()
                || !storageEndpoint().session().supports(
                StorageCapability.FLUID_STORAGE)) {
            retainCustomFluidOutput(output);
            return "custom fluid output has no writable fluid storage";
        }
        long remaining = output.amount();
        while (remaining > 0L) {
            int chunk = (int) Math.min(
                    Integer.MAX_VALUE, remaining);
            FluidStack candidate = fluidStack(
                    output.resource(), chunk);
            if (candidate.isEmpty()) {
                retainCustomFluidOutput(remaining(
                        output, remaining));
                return "custom fluid output is not registered";
            }
            StorageFluidOperationResult simulated;
            try {
                simulated = storageEndpoint().session().insertFluid(
                        player, candidate, true);
            } catch (RuntimeException | LinkageError failure) {
                retainCustomFluidOutput(remaining(
                        output, remaining));
                return "custom fluid output simulation failed";
            }
            if (simulated.transferredAmountExact().orElse(-1L)
                    != chunk) {
                retainCustomFluidOutput(remaining(
                        output, remaining));
                return "storage rejected custom fluid output";
            }
            StorageFluidOperationResult inserted =
                    storageEndpoint().session().insertFluid(
                            player, candidate, false);
            long accepted = inserted.transferredAmountExact()
                    .orElse(-1L);
            if (accepted > 0L) {
                reportedResources.add(new CollectedOutput(
                        output.resource(),
                        accepted,
                        output.outputKind(),
                        output.portId()
                ));
            }
            if (accepted != chunk) {
                long uninserted = remaining - Math.max(0L, accepted);
                if (uninserted > 0L) {
                    retainCustomFluidOutput(remaining(
                            output, uninserted));
                }
                return "storage accepted only part of custom fluid output";
            }
            remaining -= chunk;
        }
        return null;
    }

    private static FluidStack fluidStack(
            IntegrationResourceKey resource,
            int amount
    ) {
        var fluid = ForgeRegistries.FLUIDS.getValue(resource.id());
        if (fluid == null) return FluidStack.EMPTY;
        FluidStack stack = new FluidStack(fluid, amount);
        if (!resource.identity().isEmpty()) {
            stack.setTag(resource.identity());
        }
        return stack;
    }

    private static CollectedOutput remaining(
            CollectedOutput output,
            long amount
    ) {
        return new CollectedOutput(
                output.resource(),
                amount,
                output.outputKind(),
                output.portId()
        );
    }

    private void retainCustomFluidOutput(
            CollectedOutput output
    ) {
        long remaining = output.amount();
        while (remaining > 0L) {
            int chunk = (int) Math.min(
                    Integer.MAX_VALUE, remaining);
            CompoundTag fluidTag = new CompoundTag();
            fluidTag.putString(
                    "FluidName", output.resource().id().toString());
            fluidTag.putInt("Amount", chunk);
            CompoundTag identity = output.resource().identity();
            if (!identity.isEmpty()) {
                fluidTag.put("Tag", identity);
            }
            UnifiedGridFluidRecovery.get(
                    operationStartContext.player().server)
                    .retainRaw(
                            operationStartContext.player().getUUID(),
                            fluidTag,
                            null
                    );
            remaining -= chunk;
        }
    }

    private void appendItemOutput(
            IntegrationResourceKey resource,
            long amount
    ) {
        long remaining = amount;
        while (remaining > 0L) {
            int chunk = (int) Math.min(
                    Integer.MAX_VALUE, remaining);
            customCollectedItems.add(
                    ExternalRecipeProjection.toStack(resource, chunk));
            remaining -= chunk;
        }
    }

    private void resetCustomCollectionState() {
        customCollectedOutputTotals.clear();
        customCollectedMaterialTotals.clear();
        customCollectedItems.clear();
        customCollectedOutputs.clear();
        customCollectedDispositions.clear();
        customCollectionComplete = false;
    }

    private void persistCustomOperation(
            ExternalOperationJournal.State state,
            String detail
    ) {
        if (executionKind != RecipeExecutionKind.CUSTOM
                || executionPlan == null
                || customMaterials == null
                || operationStartContext == null
                || recipe == null
                || recipe.getId() == null
                || recipeIntegration == null
                || machineIntegration == null
                || executionIntegration == null
                || descriptor == null
                || recipeView == null
                || machinePos == null) {
            return;
        }
        MinecraftServer server = level == null
                ? null : level.getServer();
        if (server == null) {
            server = operationStartContext.player().getServer();
        }
        if (server == null) return;
        ResourceLocation dimension = getMachineDim();
        if (dimension == null && level != null) {
            try {
                dimension = level.dimension().location();
            } catch (RuntimeException ignored) {
                return;
            }
        }
        if (dimension == null) return;
        try {
            UUID ownerId = customOwnerId != null
                    ? customOwnerId
                    : operationStartContext.player().getUUID();
            long gameTime = level == null
                    ? server.overworld().getGameTime()
                    : level.getGameTime();
            ExternalOperationJournal journal =
                    ExternalOperationJournal.get(server);
            ExternalOperationJournal.Entry existing = journal.find(
                    operationStartContext.operationId());
            if (existing != null
                    && existing.state() == state
                    && existing.detail().equals(
                    detail == null ? "" : detail)
                    && Objects.equals(
                    existing.snapshot(), customSnapshot)
                    && existing.collectionComplete()
                    == customCollectionComplete
                    && existing.collectedOutputs().equals(
                    customCollectedOutputs)
                    && existing.collectedDispositions().equals(
                    customCollectedDispositions)) {
                return;
            }
            journal.put(new ExternalOperationJournal.Entry(
                    ownerId,
                    operationStartContext.transactionId(),
                    operationStartContext.operationId(),
                    operationStartContext.attempt(),
                    recipe.getId(),
                    dimension,
                    machinePos,
                    recipeIntegration.id(),
                    machineIntegration.id(),
                    executionIntegration.id(),
                    recipeRevision,
                    descriptor.descriptorRevision(),
                    recipeView.definitionFingerprint(),
                    descriptor.descriptorFingerprint(),
                    executionPlan,
                    customMaterials.materials(),
                    state,
                    gameTime,
                    detail,
                    customSnapshot,
                    customCollectedOutputs,
                    customCollectedDispositions,
                    customCollectionComplete
            ));
        } catch (RuntimeException | LinkageError failure) {
            LOGGER.warn(
                    "[RSI-API] failed to persist custom operation {}",
                    operationStartContext.operationId(),
                    failure
            );
        }
    }

    private boolean resumeCustomOperation(ServerLevel currentLevel) {
        OperationSnapshot snapshot = customSnapshot;
        if (snapshot == null
                || executionPlan == null
                || executionIntegration == null
                || operationStartContext == null) {
            return false;
        }
        ExecutionResult resumed;
        try {
            resumed = executionIntegration.resume(
                    new OperationContext(
                            operationStartContext.player(),
                            operationStartContext.transactionId(),
                            operationStartContext.operationId(),
                            operationStartContext.attempt(),
                            customMaterials,
                            new MachineContext(
                                    machineIntegration.id(),
                                    currentLevel,
                                    machinePos,
                                    operationStartContext.player()
                            ),
                            currentLevel.getGameTime()
                    ),
                    executionPlan,
                    snapshot
            );
        } catch (RuntimeException | LinkageError failure) {
            LOGGER.warn(
                    "[RSI-API] custom resume failed for {}",
                    recipe == null ? "unknown" : recipe.getId(),
                    failure
            );
            return false;
        }
        if (resumed.status() == ExecutionStartStatus.RETRY) {
            return true;
        }
        if (resumed.status() != ExecutionStartStatus.STARTED
                || resumed.operation() == null) {
            return false;
        }
        runningOperation = resumed.operation();
        customStartPending = false;
        captureCustomSnapshot();
        LOGGER.info(
                "[RSI-API] resumed custom operation {} at sequence {}",
                operationStartContext.operationId(),
                snapshot.collectionSequence()
        );
        return true;
    }

    private void captureCustomSnapshot() {
        if (runningOperation == null) return;
        try {
            OperationSnapshot snapshot = runningOperation.snapshot();
            if (snapshot.collectionSequence()
                    < lastCustomSnapshotSequence) {
                LOGGER.warn(
                        "[RSI-API] custom snapshot sequence moved backwards for {}",
                        operationStartContext == null
                                ? "unknown"
                                : operationStartContext.operationId()
                );
                return;
            }
            customSnapshot = snapshot;
            lastCustomSnapshotSequence =
                    snapshot.collectionSequence();
            persistCustomOperation(
                    ExternalOperationJournal.State.ACTIVE,
                    "");
        } catch (RuntimeException | LinkageError failure) {
            LOGGER.warn(
                    "[RSI-API] custom snapshot failed for {}",
                    recipe == null ? "unknown" : recipe.getId(),
                    failure
            );
        }
    }

    private List<ItemStack> collectCustomResults(ServerPlayer player) {
        if (!customCollectionComplete || resultsCollected) {
            return List.of();
        }
        resultsCollected = true;
        return customCollectedItems.stream()
                .map(ItemStack::copy)
                .toList();
    }

    private void collectFluidOutputs(ServerPlayer player) {
        if (recipeView.outputs().stream().noneMatch(output ->
                output.resource().kind()
                        == IntegrationResourceKey.ResourceKind.FLUID)) {
            return;
        }
        if (!hasStorageAccess()) {
            failReportedResource("selected storage has no fluid capability");
            return;
        }
        for (OutputDeclaration output : recipeView.outputs()) {
            if (output.resource().kind()
                    != IntegrationResourceKey.ResourceKind.FLUID) {
                continue;
            }
            long expected = output.probability().mode()
                    == OutputProbabilityMode.GUARANTEED
                    ? output.amount().min() : 0L;
            long remaining = Math.min(
                    Integer.MAX_VALUE, output.amount().max());
            for (MachineFluidPort port : fluidOutputPorts(
                    output.outputGroup())) {
                if (remaining <= 0L) break;
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
                int requested = (int) Math.min(
                        remaining, available);
                FluidStack candidate = current.copy();
                candidate.setAmount(requested);
                StorageFluidOperationResult simulated =
                        storageEndpoint().session().insertFluid(
                                player, candidate, true);
                long simulatedAccepted =
                        simulated.transferredAmountExact().orElse(-1L);
                if (simulatedAccepted != requested) {
                    failReportedResource(
                            "storage rejected fluid output before extraction");
                    return;
                }
                FluidStack extracted = extractFluid(
                        port, requested);
                if (extracted.isEmpty()) {
                    failReportedResource(
                            "machine did not release the declared fluid output");
                    return;
                }
                remaining -= extracted.getAmount();
                StorageFluidOperationResult inserted =
                        storageEndpoint().session().insertFluid(
                                player, extracted, false);
                long accepted = inserted.transferredAmountExact()
                        .orElse(-1L);
                if (accepted > 0L) {
                    reportedResources.add(new CollectedOutput(
                            IntegrationResourceKey.fluid(extracted),
                            accepted,
                            output.outputKind(),
                            output.portId()
                    ));
                }
                if (accepted != extracted.getAmount()) {
                    if (inserted.remainder() != null
                            && !inserted.remainder().isEmpty()) {
                        returnFluidToPort(port, inserted.remainder());
                    }
                    failReportedResource(
                            "storage accepted only part of the fluid output");
                    LOGGER.error(
                            "[RSI-API] failed to insert every collected fluid mB into storage: expected={}, accepted={}",
                            extracted.getAmount(), accepted);
                    return;
                }
            }
            if (expected > 0L && remaining > 0L) {
                failReportedResource(
                        "declared fluid output was not fully collected");
                return;
            }
        }
    }

    private void returnFluidToPort(
            MachineFluidPort port,
            FluidStack remainder
    ) {
        IFluidHandler handler = fluidHandler(port.side());
        if (handler == null) return;
        FluidStack returned = remainder.copy();
        int accepted = handler.fill(returned, FluidAction.EXECUTE);
        if (accepted < remainder.getAmount()) {
            LOGGER.error(
                    "[RSI-API] failed to return {} mB of fluid output to the machine",
                    remainder.getAmount() - Math.max(0, accepted));
        }
    }

    private void failReportedResource(String detail) {
        if (reportedResourceFailure == null) {
            reportedResourceFailure = detail;
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

    @Nullable
    static String validateCollectedOutputAgainstPort(
            OutputPort declared,
            CollectedOutput output,
            long alreadyCollected
    ) {
        if (!declared.portId().equals(output.portId())) {
            return "custom output port mismatch: " + output.portId();
        }
        if (declared.resource().kind() != output.resource().kind()
                || !declared.resource().id().equals(output.resource().id())
                || !matchesDeclaredIdentity(declared, output.resource())) {
            return "custom output identity mismatch: " + output.portId();
        }
        long total;
        try {
            total = Math.addExact(alreadyCollected, output.amount());
        } catch (ArithmeticException exception) {
            return "custom output total overflowed: " + output.portId();
        }
        if (total > declared.amount().max()) {
            return "custom output exceeds declared maximum: "
                    + output.portId();
        }
        return null;
    }

    private static boolean matchesDeclaredIdentity(
            OutputPort declared,
            IntegrationResourceKey actual
    ) {
        if (declared.resource().kind()
                == IntegrationResourceKey.ResourceKind.ITEM) {
            ItemStack expected = ExternalRecipeProjection.toStack(
                    declared.resource(), 1L);
            ItemStack actualStack = ExternalRecipeProjection.toStack(
                    actual, 1L);
            return MaterialMatcher.matchesOutputDeclaration(
                    MaterialKey.of(expected), actualStack);
        }
        CompoundTag declaredIdentity = declared.resource().identity();
        CompoundTag actualIdentity = actual.identity();
        if (declared.dynamicIdentity()) {
            return compoundContains(actualIdentity, declaredIdentity);
        }
        return declaredIdentity.isEmpty()
                || declaredIdentity.equals(actualIdentity);
    }

    @Nullable
    static String validateMaterialDispositionAgainstReservation(
            ReservedMaterial reserved,
            MaterialDisposition disposition,
            long alreadyDisposed
    ) {
        ReservedMaterial candidate = disposition.material();
        if (!reserved.entryId().equals(candidate.entryId())) {
            return "custom material entry mismatch: "
                    + candidate.entryId();
        }
        if (!reserved.resource().equals(candidate.resource())) {
            return "custom material identity mismatch: "
                    + candidate.entryId();
        }
        if (!reserved.consumption().equals(candidate.consumption())) {
            return "custom material consumption mismatch: "
                    + candidate.entryId();
        }
        if (candidate.amount() <= 0L) {
            return "custom material disposition amount must be positive: "
                    + candidate.entryId();
        }
        long total;
        try {
            total = Math.addExact(alreadyDisposed, candidate.amount());
        } catch (ArithmeticException exception) {
            return "custom material disposition overflowed: "
                    + candidate.entryId();
        }
        if (total > reserved.amount()) {
            return "custom material disposition exceeds reservation: "
                    + candidate.entryId();
        }
        return null;
    }

    private static boolean compoundContains(
            CompoundTag actual,
            CompoundTag expected
    ) {
        for (String key : expected.getAllKeys()) {
            Tag required = expected.get(key);
            Tag present = actual.get(key);
            if (present == null
                    || present.getId() != required.getId()) {
                return false;
            }
            if (required instanceof CompoundTag requiredCompound
                    && present instanceof CompoundTag presentCompound) {
                if (!compoundContains(presentCompound, requiredCompound)) {
                    return false;
                }
            } else if (!present.equals(required)) {
                return false;
            }
        }
        return true;
    }

    private void clearCustomOperation(@Nullable ServerPlayer player) {
        RunningOperation operation = runningOperation;
        runningOperation = null;
        if (operation == null) {
            returnOrRetainCustomFluids(customReservedFluids, player);
            customReservedFluids.clear();
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
            persistCustomOperation(
                    ExternalOperationJournal.State.FAILED,
                    "operation was not started");
            return;
        }
        try {
            OperationCleanupResult cleanup = customCancelReason == null
                    ? operation.onFailure(
                    FailureReason.EXECUTION_EXCEPTION, player)
                    : operation.onCancel(customCancelReason, player);
            customCancelReason = null;
            String cleanupFailure = validateCustomCleanup(
                    cleanup, customMaterials);
            if (cleanupFailure != null) {
                LOGGER.warn(
                        "[RSI-API] custom cleanup is not safe for {}: {}",
                        operationStartContext.operationId(),
                        cleanupFailure
                );
                retainAllCustomFluids(player);
                persistCustomOperation(
                        ExternalOperationJournal.State.UNCERTAIN,
                        cleanupFailure);
                return;
            }
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
            settleCustomFluidInputs(cleanup.materials(), player);
            persistCustomOperation(
                    ExternalOperationJournal.State.FAILED,
                    customCancelReason == null
                            ? "cleanup complete"
                            : "cancel cleanup complete");
        } catch (RuntimeException | LinkageError exception) {
            LOGGER.warn(
                    "[RSI-API] custom cleanup failed for {}",
                    recipe == null ? "unknown" : recipe.getId(),
                    exception
            );
            retainAllCustomFluids(player);
            persistCustomOperation(
                    ExternalOperationJournal.State.UNCERTAIN,
                    "cleanup exception: "
                            + exception.getClass().getName());
        }
    }

    @Nullable
    static String validateCustomCleanup(
            OperationCleanupResult cleanup,
            ReservedMaterialView materials
    ) {
        if (!cleanup.cleanupComplete()) {
            return "cleanupComplete=false";
        }
        Map<String, Long> totals = new LinkedHashMap<>();
        for (MaterialDisposition disposition : cleanup.materials()) {
            ReservedMaterial reserved = materials.find(
                    disposition.material().entryId()).orElse(null);
            if (reserved == null) {
                return "undeclared material "
                        + disposition.material().entryId();
            }
            long previous = totals.getOrDefault(
                    reserved.entryId(), 0L);
            String failure = validateMaterialDispositionAgainstReservation(
                    reserved, disposition, previous);
            if (failure != null) return failure;
            long total;
            try {
                total = Math.addExact(
                        previous, disposition.material().amount());
            } catch (ArithmeticException exception) {
                return "material disposition overflowed: "
                        + reserved.entryId();
            }
            totals.put(reserved.entryId(), total);
        }
        for (ReservedMaterial material : materials.materials()) {
            if (totals.getOrDefault(material.entryId(), 0L)
                    != material.amount()) {
                return "cleanup did not account for reserved material "
                        + material.entryId();
            }
        }
        return null;
    }

    private void retainAllCustomFluids(@Nullable ServerPlayer player) {
        if (customReservedFluids.isEmpty()) return;
        retainCustomFluids(Map.copyOf(customReservedFluids), player);
        customReservedFluids.clear();
    }

    private void settleCustomFluidInputs(
            List<MaterialDisposition> dispositions,
            @Nullable ServerPlayer player
    ) {
        if (customReservedFluids.isEmpty()) return;
        Set<String> handled = new HashSet<>();
        for (MaterialDisposition disposition : dispositions) {
            ReservedMaterial material = disposition.material();
            if (material.resource().kind()
                    != IntegrationResourceKey.ResourceKind.FLUID) {
                continue;
            }
            String entryId = material.entryId();
            List<FluidStack> reserved = customReservedFluids.get(entryId);
            if (reserved == null || !handled.add(entryId)) continue;
            switch (disposition.outcome()) {
                case CONSUMED -> {
                    // 已由 custom operation 明确消费。
                }
                case RECOVERED ->
                        returnOrRetainCustomFluids(
                                Map.of(entryId, reserved), player);
                case UNCERTAIN -> retainCustomFluids(
                        Map.of(entryId, reserved), player);
            }
            customReservedFluids.remove(entryId);
        }
        if (!customReservedFluids.isEmpty()) {
            retainCustomFluids(
                    Map.copyOf(customReservedFluids), player);
            customReservedFluids.clear();
        }
    }

    private void returnOrRetainCustomFluids(
            Map<String, List<FluidStack>> fluids,
            @Nullable ServerPlayer player
    ) {
        if (!hasStorageAccess() || player == null) {
            retainCustomFluids(fluids, player);
            return;
        }
        Map<String, List<FluidStack>> unreturned =
                new LinkedHashMap<>();
        for (Map.Entry<String, List<FluidStack>> entry : fluids.entrySet()) {
            List<FluidStack> remainder = new ArrayList<>();
            for (FluidStack fluid : entry.getValue()) {
                StorageFluidOperationResult inserted =
                        storageEndpoint().session().insertFluid(
                                player, fluid.copy(), false);
                if (inserted.remainder() != null
                        && !inserted.remainder().isEmpty()) {
                    remainder.add(inserted.remainder().copy());
                }
            }
            if (!remainder.isEmpty()) {
                unreturned.put(entry.getKey(), List.copyOf(remainder));
            }
        }
        if (!unreturned.isEmpty()) {
            retainCustomFluids(unreturned, player);
        }
    }

    private void retainCustomFluids(
            Map<String, List<FluidStack>> fluids,
            @Nullable ServerPlayer player
    ) {
        MinecraftServer server = player != null
                ? player.server
                : level == null ? null : level.getServer();
        if (server == null || customOwnerId == null) {
            LOGGER.error(
                    "[RSI-API] custom fluid recovery is unavailable for {} entries",
                    fluids.size());
            return;
        }
        UnifiedGridFluidRecovery recovery =
                UnifiedGridFluidRecovery.get(server);
        for (List<FluidStack> fragments : fluids.values()) {
            for (FluidStack fluid : fragments) {
                recovery.retainRaw(
                        customOwnerId,
                        fluid.writeToNBT(new CompoundTag()),
                        null
                );
            }
        }
    }

    private ItemStack insertMaterial(
            ServerPlayer player,
            MaterialRequirement requirement,
            ItemStack stack
    ) {
        List<MachineItemPort> ports = descriptor.itemPorts().stream()
                .filter(port -> port.direction() == InputOutputAccess.INPUT)
                .filter(port -> matchesGroup(
                        port.inputGroup(), requirement.inputGroup()))
                .sorted(groupPriority(
                        MachineItemPort::portId,
                        MachineItemPort::inputGroup,
                        requirement.inputGroup()))
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

    private boolean reserveCustomFluids(
            ServerPlayer player,
            List<ReservedMaterial> reservedMaterials
    ) {
        if (recipeView.fluids().isEmpty()) return true;
        if (!hasStorageAccess()
                || !storageEndpoint().session().supports(
                StorageCapability.FLUID_STORAGE)) {
            preparationFailure = Component.literal(
                    "RSI Integration custom execution requires fluid storage");
            return false;
        }
        var snapshotResult = storageEndpoint().session().snapshotFluids(player);
        if (!snapshotResult.successful()) {
            preparationFailure = Component.literal(
                    "RSI Integration custom fluid snapshot failed: "
                            + snapshotResult.status());
            return false;
        }
        StorageFluidSnapshot snapshot =
                snapshotResult.snapshotValue().orElseThrow();
        Map<StorageFluidKey, Long> selected = new LinkedHashMap<>();
        for (FluidRequirement requirement : recipeView.fluids()) {
            StorageFluidKey key = ExternalFluidMatcher.find(
                    requirement, snapshot, selected);
            if (key == null) {
                refundCustomFluids(player);
                preparationFailure = Component.literal(
                        "RSI Integration custom fluid is unavailable: "
                                + requirement.entryId());
                return false;
            }
            ExternalFluidMatcher.reserve(
                    selected, key, requirement.amount());
            StorageFluidOperationResult simulated =
                    storageEndpoint().session().extractFluidExact(
                            player, key, requirement.amount(), true);
            if (simulated.status() != StorageOperationStatus.SUCCESS
                    || simulated.transferredAmountExact().orElse(-1L)
                    != requirement.amount()) {
                refundCustomFluids(player);
                preparationFailure = Component.literal(
                        "RSI Integration custom fluid preflight failed: "
                                + requirement.entryId());
                return false;
            }
            StorageFluidOperationResult extracted =
                    storageEndpoint().session().extractFluidExact(
                            player, key, requirement.amount(), false);
            if (extracted.status() != StorageOperationStatus.SUCCESS
                    || extracted.transferredAmountExact().orElse(-1L)
                    != requirement.amount()
                    || extracted.transferredFluids().isEmpty()) {
                refundFluids(player, extracted.transferredFluids());
                refundCustomFluids(player);
                preparationFailure = Component.literal(
                        "RSI Integration custom fluid extraction failed: "
                                + requirement.entryId());
                return false;
            }
            List<FluidStack> fragments = extracted.transferredFluids()
                    .stream().map(FluidStack::copy).toList();
            customReservedFluids.put(
                    requirement.entryId().toString(), fragments);
            reservedMaterials.add(new ReservedMaterial(
                    requirement.entryId().toString(),
                    IntegrationResourceKey.fluid(fragments.get(0)),
                    requirement.amount(),
                    requirement.consumption()
            ));
        }
        return true;
    }

    private void refundCustomFluids(@Nullable ServerPlayer player) {
        if (customReservedFluids.isEmpty()) return;
        List<FluidStack> fluids = customReservedFluids.values().stream()
                .flatMap(List::stream)
                .map(FluidStack::copy)
                .toList();
        customReservedFluids.clear();
        refundFluids(player, fluids);
    }

    private long insertFluidIntoMachine(
            FluidRequirement requirement,
            FluidStack fluid
    ) {
        if (descriptor == null || fluid.isEmpty()) return fluid.getAmount();
        long remaining = fluid.getAmount();
        List<MachineFluidPort> ports = descriptor.fluidPorts().stream()
                .filter(port -> port.direction() == InputOutputAccess.INPUT)
                .filter(port -> matchesGroup(
                        port.inputGroup(), requirement.inputGroup()))
                .filter(port -> port.access() != FluidPortAccess.CUSTOM)
                .sorted(groupPriority(
                        MachineFluidPort::portId,
                        MachineFluidPort::inputGroup,
                        requirement.inputGroup()))
                .toList();
        for (MachineFluidPort port : ports) {
            if (remaining <= 0L) break;
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
            LOGGER.error(
                    "[RSI-API] failed to return {} mB of {} to storage",
                    refund.remainder().getAmount(),
                    refund.remainder().getFluid()
            );
        }
    }

    private int takeMatchingInputCount(
            MaterialRequirement requirement,
            ItemStack material,
            Map<SlotKey, Integer> consumedBaselineInputs
    ) {
        if (descriptor == null || material.isEmpty()) return 0;
        var ingredient = ExternalRecipeProjection.ingredient(
                requirement.ingredient());
        int remaining = material.getCount();
        for (MachineItemPort port : descriptor.itemPorts()) {
            if (remaining <= 0) break;
            if (port.direction() != InputOutputAccess.INPUT
                    || !matchesGroup(
                    port.inputGroup(), requirement.inputGroup())) {
                continue;
            }
            SlotKey slot = new SlotKey(port);
            ItemStack baseline = inputBaseline.get(slot);
            if (baseline == null
                    || baseline.isEmpty()
                    || !ingredient.test(baseline)) continue;
            int consumed = consumedBaselineInputs.getOrDefault(slot, 0);
            int available = Math.max(0, baseline.getCount() - consumed);
            int taken = Math.min(remaining, available);
            if (taken <= 0) continue;
            consumedBaselineInputs.put(slot, consumed + taken);
            remaining -= taken;
        }
        return material.getCount() - remaining;
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
            LOGGER.warn(
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
                long maximum = plannedOutputMaximum(output);
                FluidStack expected = fluidStack(
                        output.resource(),
                        Math.min(Integer.MAX_VALUE, maximum)
                );
                long capacity = 0L;
                for (MachineFluidPort port : fluidOutputPorts(
                        output.outputGroup())) {
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
            long maximum = Math.min(
                    Integer.MAX_VALUE, plannedOutputMaximum(output));
            ItemStack expected = ExternalRecipeProjection.toStack(
                    output.resource(), maximum);
            long capacity = 0L;
            for (MachineItemPort port : outputPorts(output.outputGroup())) {
                capacity += outputCapacityFor(port, expected);
                if (capacity >= maximum) break;
            }
            if (capacity < maximum) return false;
        }
        return true;
    }

    private List<MachineItemPort> outputPorts(String outputGroup) {
        return descriptor.itemPorts().stream()
                .filter(port -> port.direction() == InputOutputAccess.OUTPUT)
                .filter(port -> matchesGroup(port.outputGroup(), outputGroup))
                .sorted(groupPriority(
                        MachineItemPort::portId,
                        MachineItemPort::outputGroup,
                        outputGroup))
                .toList();
    }

    private List<MachineFluidPort> fluidOutputPorts(String outputGroup) {
        return descriptor.fluidPorts().stream()
                .filter(port -> port.direction() == InputOutputAccess.OUTPUT)
                .filter(port -> matchesGroup(port.outputGroup(), outputGroup))
                .sorted(groupPriority(
                        MachineFluidPort::portId,
                        MachineFluidPort::outputGroup,
                        outputGroup))
                .toList();
    }

    private static boolean matchesGroup(
            @Nullable String configured,
            String required
    ) {
        return configured == null || configured.equals(required);
    }

    static int batchOperationLimit(
            @Nullable RecipeView recipe,
            @Nullable MachineDescriptor descriptor
    ) {
        if (recipe == null || descriptor == null
                || !recipe.flags().supportsBatching()
                || !recipe.flags().deterministicOutput()
                || !recipe.fluids().isEmpty()
                || descriptor.executionMode()
                == MachineExecutionMode.CUSTOM) {
            return 1;
        }
        for (OutputDeclaration output : recipe.outputs()) {
            if (output.resource().kind()
                    != IntegrationResourceKey.ResourceKind.ITEM
                    || output.dynamicIdentity()
                    || output.probability().mode()
                    != OutputProbabilityMode.GUARANTEED) {
                return 1;
            }
        }
        long limit = Integer.MAX_VALUE;
        for (MaterialRequirement material : recipe.materials()) {
            if (material.amount() <= 0L
                    || material.amount() > Integer.MAX_VALUE) {
                return 1;
            }
            long capacity = 0L;
            for (MachineItemPort port : descriptor.itemPorts()) {
                if (port.direction() != InputOutputAccess.INPUT
                        || !matchesGroup(
                        port.inputGroup(), material.inputGroup())) {
                    continue;
                }
                capacity += port.capacity();
            }
            if (capacity <= 0L) return 1;
            limit = Math.min(limit, capacity / material.amount());
        }
        for (OutputDeclaration output : recipe.outputs()) {
            long perOperation = Math.max(1L, output.amount().min());
            long capacity = 0L;
            for (MachineItemPort port : descriptor.itemPorts()) {
                if (port.direction() != InputOutputAccess.OUTPUT
                        || !matchesGroup(
                        port.outputGroup(), output.outputGroup())) {
                    continue;
                }
                capacity += port.capacity();
            }
            if (capacity <= 0L) return 1;
            limit = Math.min(limit, capacity / perOperation);
        }
        return (int) Math.max(1L, Math.min(Integer.MAX_VALUE, limit));
    }

    private long plannedOutputMinimum(OutputDeclaration output) {
        return multiplySaturating(
                output.amount().min(), plannedBatchOperations);
    }

    private long plannedOutputMaximum(OutputDeclaration output) {
        return multiplySaturating(
                output.amount().max(), plannedBatchOperations);
    }

    private static long multiplySaturating(long value, int multiplier) {
        if (value <= 0L || multiplier <= 1) return value;
        return value > Long.MAX_VALUE / multiplier
                ? Long.MAX_VALUE : value * multiplier;
    }

    private static <T> Comparator<T> groupPriority(
            Function<T, String> portId,
            Function<T, String> group,
            String required
    ) {
        return Comparator
                .comparingInt((T value) ->
                        Objects.equals(group.apply(value), required) ? 0 : 1)
                .thenComparing(portId);
    }

    private boolean hasNewExpectedOutput() {
        boolean sawOutput = false;
        for (OutputDeclaration output : recipeView.outputs()) {
            if (output.resource().kind()
                    != IntegrationResourceKey.ResourceKind.ITEM) {
                continue;
            }
            sawOutput = true;
            long produced = 0L;
            for (MachineItemPort port : outputPorts(output.outputGroup())) {
                ItemStack current = get(port, null);
                ItemStack before = outputBaseline.get(new SlotKey(port));
                int baseline = before == null ? 0 : before.getCount();
                if (matches(current, output)) {
                    produced += Math.max(
                            0, current.getCount() - baseline);
                }
            }
            if (produced < plannedOutputMinimum(output)) return false;
        }
        return sawOutput;
    }

    private boolean hasNewExpectedFluidOutput() {
        for (OutputDeclaration output : recipeView.outputs()) {
            if (output.resource().kind()
                    != IntegrationResourceKey.ResourceKind.FLUID) {
                continue;
            }
            for (MachineFluidPort port : fluidOutputPorts(
                    output.outputGroup())) {
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
        if (port.access() == ItemPortAccess.ITEM_HANDLER
                || port.access() == ItemPortAccess.MACHINE_INVENTORY) {
            IItemHandler handler = itemHandler(port);
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

    private long outputCapacityFor(
            MachineItemPort port,
            ItemStack expected
    ) {
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
        if (port.access() == ItemPortAccess.ITEM_HANDLER
                || port.access() == ItemPortAccess.MACHINE_INVENTORY) {
            IItemHandler handler = itemHandler(port);
            if (handler == null) return 0L;
            ItemStack current = handler.getStackInSlot(port.slot());
            if (!current.isEmpty()
                    && !ItemStack.isSameItemSameTags(current, expected)) {
                return 0L;
            }
            int limit = Math.min(expected.getMaxStackSize(),
                    Math.min(handler.getSlotLimit(port.slot()), port.capacity()));
            return Math.max(0, limit - current.getCount());
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
        if (port.access() == ItemPortAccess.ITEM_HANDLER
                || port.access() == ItemPortAccess.MACHINE_INVENTORY) {
            IItemHandler handler = itemHandler(port);
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
        if (port.access() == ItemPortAccess.ITEM_HANDLER
                || port.access() == ItemPortAccess.MACHINE_INVENTORY) {
            IItemHandler handler = itemHandler(port);
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
    private IItemHandler itemHandler(MachineItemPort port) {
        if (port.access() != ItemPortAccess.MACHINE_INVENTORY) {
            return itemHandler(port.side());
        }
        if (machineIntegration == null || machineContext == null) {
            return null;
        }
        BlockEntity blockEntity = blockEntity();
        if (blockEntity == null) return null;
        try {
            return machineIntegration.internalItemHandler(
                    machineContext, blockEntity).orElse(null);
        } catch (RuntimeException | LinkageError failure) {
            LOGGER.warn(
                    "[RSI-API] internal item handler failed for {}",
                    port.portId(),
                    failure
            );
            return null;
        }
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
                            && matchesGroup(
                            port.inputGroup(), material.inputGroup())
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
                            && matchesGroup(
                            port.outputGroup(), output.outputGroup())
                            && port.access() != ItemPortAccess.CUSTOM);
            if (!matched) {
                failures.add(new IntegrationDiagnosticMessage(
                        "no item output port for group " + output.outputGroup()));
            }
        }
        for (FluidRequirement fluid : view.fluids()) {
            boolean matched = descriptor.fluidPorts().stream().anyMatch(port ->
                    port.direction() == InputOutputAccess.INPUT
                            && matchesGroup(
                            port.inputGroup(), fluid.inputGroup())
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
                            && matchesGroup(
                            port.outputGroup(), output.outputGroup())
                            && port.access() != FluidPortAccess.CUSTOM);
            if (!matched) {
                failures.add(new IntegrationDiagnosticMessage(
                        "no fluid output port for group "
                                + output.outputGroup()));
            }
        }
        for (MachineItemPort port : descriptor.itemPorts()) {
            String unavailable = validateItemPortBinding(port);
            if (unavailable != null) {
                failures.add(new IntegrationDiagnosticMessage(unavailable));
            }
        }
        for (MachineFluidPort port : descriptor.fluidPorts()) {
            String unavailable = validateFluidPortBinding(port);
            if (unavailable != null) {
                failures.add(new IntegrationDiagnosticMessage(unavailable));
            }
        }
        return List.copyOf(failures);
    }

    @Nullable
    private String validateCurrentDescriptor() {
        if (machineIntegration == null || descriptor == null) {
            return "machine descriptor context is missing";
        }
        BlockEntity blockEntity = blockEntity();
        if (blockEntity == null || blockEntity.isRemoved()) {
            return "machine block entity is missing";
        }
        MachineContext context = new MachineContext(
                machineIntegration.id(),
                level,
                machinePos,
                operationStartContext == null
                        ? null : operationStartContext.player()
        );
        MachineDescriptorResult current;
        try {
            current = machineIntegration.describe(context, blockEntity);
        } catch (RuntimeException | LinkageError failure) {
            return "machine describe failed during operation";
        }
        if (current.descriptor() == null) {
            return "machine descriptor became unavailable";
        }
        MachineDescriptor next = current.descriptor();
        if (next.descriptorRevision() != descriptor.descriptorRevision()
                || !next.descriptorFingerprint().equals(
                descriptor.descriptorFingerprint())) {
            return IntegrationDiagnosticCode.MACHINE_DESCRIPTOR_CHANGED.name();
        }
        return null;
    }

    @Nullable
    private String validateItemPortBinding(MachineItemPort port) {
        if (port.access() == ItemPortAccess.CUSTOM) {
            return "custom item port requires custom execution";
        }
        if (port.access() == ItemPortAccess.CONTAINER) {
            Container container = container();
            return container != null
                    && port.slot() < container.getContainerSize()
                    ? null
                    : "item container port unavailable: " + port.portId();
        }
        if (port.access() != ItemPortAccess.ITEM_HANDLER
                && port.access() != ItemPortAccess.MACHINE_INVENTORY) {
            return "unsupported item port access: " + port.portId();
        }
        IItemHandler handler = itemHandler(port);
        return handler != null && port.slot() < handler.getSlots()
                ? null
                : "item handler port unavailable: " + port.portId();
    }

    @Nullable
    private String validateFluidPortBinding(MachineFluidPort port) {
        if (port.access() == FluidPortAccess.CUSTOM) {
            return "custom fluid port requires custom execution";
        }
        IFluidHandler handler = fluidHandler(port.side());
        return handler != null && port.tank() < handler.getTanks()
                ? null
                : "fluid handler port unavailable: " + port.portId();
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

    private static BatchConcurrencyCapabilities.MaterialOwnership materials(
            ConcurrencyCapabilities.MaterialOwnership value
    ) {
        return switch (value) {
            case CHAIN_RESERVED ->
                    BatchConcurrencyCapabilities.MaterialOwnership.CHAIN_RESERVED;
            case SELF_EXTRACTING ->
                    BatchConcurrencyCapabilities.MaterialOwnership.SELF_EXTRACTING;
            case UNKNOWN ->
                    BatchConcurrencyCapabilities.MaterialOwnership.UNKNOWN;
        };
    }

    private static BatchConcurrencyCapabilities.OutputOwnership outputOwnership(
            ConcurrencyCapabilities.OutputOwnership value
    ) {
        return switch (value) {
            case MACHINE_SLOT ->
                    BatchConcurrencyCapabilities.OutputOwnership.MACHINE_SLOT;
            case DELEGATE_RESULT ->
                    BatchConcurrencyCapabilities.OutputOwnership.DELEGATE_RESULT;
            case OWNED_WORLD_CAPTURE ->
                    BatchConcurrencyCapabilities.OutputOwnership.OWNED_WORLD_CAPTURE;
            case ENTITY ->
                    BatchConcurrencyCapabilities.OutputOwnership.ENTITY;
            case AMBIGUOUS ->
                    BatchConcurrencyCapabilities.OutputOwnership.AMBIGUOUS;
        };
    }

    private static BatchConcurrencyCapabilities.CleanupContract cleanupContract(
            ConcurrencyCapabilities.CleanupContract value
    ) {
        return switch (value) {
            case SEPARABLE_OFFLINE ->
                    BatchConcurrencyCapabilities.CleanupContract.SEPARABLE_OFFLINE;
            case ONLINE_ONLY ->
                    BatchConcurrencyCapabilities.CleanupContract.ONLINE_ONLY;
            case UNKNOWN ->
                    BatchConcurrencyCapabilities.CleanupContract.UNKNOWN;
        };
    }

    private static BatchConcurrencyCapabilities.SideEffects sideEffects(
            ConcurrencyCapabilities.SideEffects value
    ) {
        return switch (value) {
            case NONE ->
                    BatchConcurrencyCapabilities.SideEffects.NONE;
            case MACHINE_LOCAL ->
                    BatchConcurrencyCapabilities.SideEffects.MACHINE_LOCAL;
            case LOCAL_WORLD_ITEMS ->
                    BatchConcurrencyCapabilities.SideEffects.LOCAL_WORLD_ITEMS;
            case ADJACENT_MACHINE ->
                    BatchConcurrencyCapabilities.SideEffects.ADJACENT_MACHINE;
            case PLAYER_TRANSFORM ->
                    BatchConcurrencyCapabilities.SideEffects.PLAYER_TRANSFORM;
            case WORLD_GLOBAL ->
                    BatchConcurrencyCapabilities.SideEffects.WORLD_GLOBAL;
            case UNKNOWN ->
                    BatchConcurrencyCapabilities.SideEffects.UNKNOWN;
        };
    }

    private static BatchConcurrencyCapabilities.PreparationContract preparationContract(
            ConcurrencyCapabilities.PreparationContract value
    ) {
        return switch (value) {
            case RELIABLE ->
                    BatchConcurrencyCapabilities.PreparationContract.RELIABLE;
            case RETRY_SAFE ->
                    BatchConcurrencyCapabilities.PreparationContract.RETRY_SAFE;
            case UNKNOWN ->
                    BatchConcurrencyCapabilities.PreparationContract.UNKNOWN;
        };
    }

    private static List<BlockPos> supportOffsets(SupportScope scope) {
        LinkedHashSet<BlockPos> offsets = new LinkedHashSet<>();
        for (BlockPos offset : scope.supportOffsets()) {
            offsets.add(offset.immutable());
        }
        for (BlockPos offset : scope.adjacentBlocks()) {
            offsets.add(offset.immutable());
        }
        return List.copyOf(offsets);
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
