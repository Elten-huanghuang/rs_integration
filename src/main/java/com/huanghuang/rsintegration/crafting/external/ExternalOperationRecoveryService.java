package com.huanghuang.rsintegration.crafting.external;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.api.IntegrationResourceKey;
import com.huanghuang.rsintegration.api.RSIIntegrationApi;
import com.huanghuang.rsintegration.api.diagnostic.IntegrationDiagnostic;
import com.huanghuang.rsintegration.api.execution.CollectedOutput;
import com.huanghuang.rsintegration.api.execution.CraftObservation;
import com.huanghuang.rsintegration.api.execution.ExecutionClaim;
import com.huanghuang.rsintegration.api.execution.ExecutionPlan;
import com.huanghuang.rsintegration.api.execution.ExecutionResult;
import com.huanghuang.rsintegration.api.execution.ExecutionStartStatus;
import com.huanghuang.rsintegration.api.execution.FailureReason;
import com.huanghuang.rsintegration.api.execution.MaterialDisposition;
import com.huanghuang.rsintegration.api.execution.MaterialOutcome;
import com.huanghuang.rsintegration.api.execution.OperationCleanupResult;
import com.huanghuang.rsintegration.api.execution.OperationCollection;
import com.huanghuang.rsintegration.api.execution.OperationContext;
import com.huanghuang.rsintegration.api.execution.OperationSnapshot;
import com.huanghuang.rsintegration.api.execution.OutputPort;
import com.huanghuang.rsintegration.api.execution.RecipeExecutionIntegration;
import com.huanghuang.rsintegration.api.execution.ReservedMaterial;
import com.huanghuang.rsintegration.api.execution.ReservedMaterialView;
import com.huanghuang.rsintegration.api.execution.RunningOperation;
import com.huanghuang.rsintegration.api.machine.MachineClaim;
import com.huanghuang.rsintegration.api.machine.MachineContext;
import com.huanghuang.rsintegration.api.machine.MachineDescriptor;
import com.huanghuang.rsintegration.api.machine.MachineDescriptorResult;
import com.huanghuang.rsintegration.api.machine.MachineIntegration;
import com.huanghuang.rsintegration.api.recipe.OutputKind;
import com.huanghuang.rsintegration.api.recipe.OutputProbabilityMode;
import com.huanghuang.rsintegration.api.recipe.RecipeClaim;
import com.huanghuang.rsintegration.api.recipe.RecipeExecutionKind;
import com.huanghuang.rsintegration.api.recipe.RecipeIntegration;
import com.huanghuang.rsintegration.api.recipe.RecipeView;
import com.huanghuang.rsintegration.crafting.CraftItemRecovery;
import com.huanghuang.rsintegration.feature.unifiedgrid.UnifiedGridFluidRecovery;
import com.huanghuang.rsintegration.util.PlayerUtils;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 服务端主线程上的公共自定义操作恢复驱动。
 *
 * <p>恢复只使用 journal 中的身份、计划、材料和快照；配方、机器和执行器
 * 每次都会重新 claim，绝不反序列化旧世界对象。</p>
 */
public final class ExternalOperationRecoveryService {
    private static final Logger LOGGER =
            LogManager.getLogger("RS Integration");
    private static final int MAX_DRIVERS = 8;
    private static final Map<MinecraftServer, Runtime> RUNTIMES =
            new ConcurrentHashMap<>();

    private ExternalOperationRecoveryService() {
    }

    public static void serverStarted(MinecraftServer server) {
        Runtime runtime = new Runtime(server);
        RUNTIMES.put(server, runtime);
        runtime.enqueueRecoverable();
    }

    public static void serverStopping(MinecraftServer server) {
        Runtime runtime = RUNTIMES.remove(server);
        if (runtime != null) runtime.clear();
    }

    public static void tick(MinecraftServer server) {
        Runtime runtime = RUNTIMES.get(server);
        if (runtime != null) runtime.tick();
    }

    private static final class Runtime {
        private final MinecraftServer server;
        private final ExternalOperationJournal journal;
        private final Deque<UUID> pending = new ArrayDeque<>();
        private final Set<UUID> queued = new HashSet<>();
        private final Map<UUID, Driver> drivers = new LinkedHashMap<>();

        private Runtime(MinecraftServer server) {
            this.server = server;
            journal = ExternalOperationJournal.get(server);
        }

        private void enqueueRecoverable() {
            for (ExternalOperationJournal.Entry entry
                    : journal.recoverableEntries()) {
                enqueue(entry.operationId());
            }
        }

        private void enqueue(UUID operationId) {
            if (operationId == null || !queued.add(operationId)) return;
            pending.addLast(operationId);
        }

        private void tick() {
            List<Driver> snapshot = List.copyOf(drivers.values());
            for (Driver driver : snapshot) {
                driver.tick(server, this);
            }
            if (drivers.size() >= MAX_DRIVERS) return;
            UUID operationId = pending.pollFirst();
            if (operationId == null) return;
            queued.remove(operationId);
            ExternalOperationJournal.Entry entry =
                    journal.find(operationId);
            if (entry == null || !entry.recoverable()) return;
            if (entry.state() == ExternalOperationJournal.State.ACTIVE
                    && drivers.containsKey(operationId)) {
                enqueue(operationId);
                return;
            }
            recoverOne(server, entry);
        }

        private void recoverOne(
                MinecraftServer server,
                ExternalOperationJournal.Entry entry
        ) {
            RecoveryContext context = resolve(
                    server, entry);
            if (context == null) return;
            try {
                ExecutionResult result;
                if (entry.state()
                        == ExternalOperationJournal.State.PRE_START) {
                    result = context.execution.start(
                            context.operationContext(),
                            entry.plan()
                    );
                } else {
                    if (entry.snapshot() == null) {
                        fail(entry, ExternalOperationJournal.State.UNCERTAIN,
                                "active operation has no snapshot");
                        return;
                    }
                    result = context.execution.resume(
                            context.operationContext(),
                            entry.plan(),
                            entry.snapshot()
                    );
                }
                if (result.status() == ExecutionStartStatus.RETRY) {
                    journal.put(entry.withAttempt(
                            entry.attempt() + 1,
                            context.level().getGameTime()
                    ));
                    enqueue(entry.operationId());
                    return;
                }
                if (result.status() != ExecutionStartStatus.STARTED
                        || result.operation() == null) {
                    fail(entry, ExternalOperationJournal.State.UNCERTAIN,
                            describe(result.diagnostics()));
                    return;
                }
                Driver driver = new Driver(
                        entry,
                        entry.plan(),
                        context.materials(),
                        result.operation()
                );
                drivers.put(entry.operationId(), driver);
                driver.persistActive(journal, context.level(), "");
            } catch (RuntimeException | LinkageError failure) {
                LOGGER.warn(
                        "[RSI-API] failed to resume custom operation {}",
                        entry.operationId(),
                        failure
                );
                fail(entry, ExternalOperationJournal.State.UNCERTAIN,
                        "resume exception: "
                                + failure.getClass().getName());
            }
        }

        @Nullable
        private RecoveryContext resolve(
                MinecraftServer server,
                ExternalOperationJournal.Entry entry
        ) {
            ServerPlayer player = server.getPlayerList()
                    .getPlayer(entry.ownerId());
            if (player == null || player.hasDisconnected()) {
                enqueue(entry.operationId());
                return null;
            }
            ServerLevel level = server.getLevel(ResourceKey.create(
                    Registries.DIMENSION, entry.dimension()));
            if (level == null || !level.hasChunkAt(entry.machinePos())) {
                enqueue(entry.operationId());
                return null;
            }
            BlockEntity blockEntity =
                    level.getBlockEntity(entry.machinePos());
            if (blockEntity == null || blockEntity.isRemoved()) {
                fail(entry, ExternalOperationJournal.State.UNCERTAIN,
                        "machine block entity is missing");
                return null;
            }
            RSIIntegrationApi api = RSIIntegrationApi.get().orElse(null);
            if (api == null) {
                enqueue(entry.operationId());
                return null;
            }
            Recipe<?> liveRecipe = level.getRecipeManager()
                    .byKey(entry.recipeId()).orElse(null);
            if (liveRecipe == null) {
                fail(entry, ExternalOperationJournal.State.UNCERTAIN,
                        "recipe is missing");
                return null;
            }
            RecipeClaim recipeClaim = api.recipes().claim(liveRecipe);
            RecipeIntegration<?> recipeIntegration =
                    recipeClaim.winner();
            if (recipeIntegration == null
                    || !entry.recipeIntegrationId().equals(
                    recipeIntegration.id())
                    || recipeIntegration.executionKind()
                    != RecipeExecutionKind.CUSTOM) {
                fail(entry, ExternalOperationJournal.State.UNCERTAIN,
                        "recipe integration claim changed");
                return null;
            }
            ExternalRecipeProjection.Result projection =
                    ExternalRecipeProjection.inspect(
                            recipeIntegration,
                            liveRecipe,
                            level.registryAccess()
                    );
            if (!projection.available()) {
                fail(entry, ExternalOperationJournal.State.UNCERTAIN,
                        "recipe projection is unavailable");
                return null;
            }
            RecipeView view = projection.view();
            if (!view.definitionFingerprint().equals(
                    entry.recipeFingerprint())) {
                fail(entry, ExternalOperationJournal.State.UNCERTAIN,
                        "recipe fingerprint changed");
                return null;
            }
            MachineClaim machineClaim =
                    api.machines().claim(blockEntity);
            MachineIntegration machine = machineClaim.winner();
            if (machine == null
                    || !entry.machineIntegrationId().equals(
                    machine.id())) {
                fail(entry, ExternalOperationJournal.State.UNCERTAIN,
                        "machine integration claim changed");
                return null;
            }
            MachineContext machineContext = new MachineContext(
                    machine.id(), level, entry.machinePos(), player);
            MachineDescriptorResult descriptorResult;
            try {
                descriptorResult =
                        machine.describe(machineContext, blockEntity);
            } catch (RuntimeException | LinkageError failure) {
                fail(entry, ExternalOperationJournal.State.UNCERTAIN,
                        "machine descriptor exception: "
                                + failure.getClass().getName());
                return null;
            }
            MachineDescriptor descriptor =
                    descriptorResult.descriptor();
            if (descriptor == null
                    || !descriptor.acceptedProcesses().contains(
                    view.processId())
                    || descriptor.descriptorRevision()
                    != entry.descriptorRevision()
                    || !descriptor.descriptorFingerprint().equals(
                    entry.descriptorFingerprint())
                    || !descriptor.machineId().equals(
                    entry.plan().machineId())) {
                fail(entry, ExternalOperationJournal.State.UNCERTAIN,
                        "machine descriptor changed");
                return null;
            }
            ExecutionClaim executionClaim = api.executions().claim(
                    view, machineContext);
            RecipeExecutionIntegration execution =
                    executionClaim.winner();
            if (execution == null
                    || !entry.executionIntegrationId().equals(
                    execution.id())) {
                fail(entry, ExternalOperationJournal.State.UNCERTAIN,
                        "execution integration claim changed");
                return null;
            }
            MaterialsView materials =
                    new MaterialsView(entry.reservedMaterials());
            ExecutionPlan plan = entry.plan();
            if (!plan.operationId().equals(entry.operationId())
                    || !plan.recipeId().equals(entry.recipeId())
                    || !plan.recipeIntegrationId().equals(
                    entry.recipeIntegrationId())
                    || !plan.machineIntegrationId().equals(
                    entry.machineIntegrationId())
                    || !plan.executionIntegrationId().equals(
                    entry.executionIntegrationId())
                    || plan.recipeRevision()
                    != entry.recipeRevision()
                    || plan.descriptorRevision()
                    != entry.descriptorRevision()
                    || !plan.recipeFingerprint().equals(
                    entry.recipeFingerprint())
                    || !plan.descriptorFingerprint().equals(
                    entry.descriptorFingerprint())) {
                fail(entry, ExternalOperationJournal.State.UNCERTAIN,
                        "persisted plan identity is inconsistent");
                return null;
            }
            String planFailure =
                    ExternalIntegrationBatchDelegate
                            .validateCustomPlanAgainstRecipe(
                                    view,
                                    materials,
                                    entry.plan()
                            );
            if (planFailure != null) {
                fail(entry, ExternalOperationJournal.State.UNCERTAIN,
                        planFailure);
                return null;
            }
            OperationContext operationContext = new OperationContext(
                    player,
                    entry.transactionId(),
                    entry.operationId(),
                    entry.attempt(),
                    materials,
                    machineContext,
                    level.getGameTime()
            );
            return new RecoveryContext(
                    level, execution, materials, operationContext);
        }

        private void fail(
                ExternalOperationJournal.Entry entry,
                ExternalOperationJournal.State state,
                String detail
        ) {
            journal.put(entry.withState(
                    state, gameTimeFor(entry), detail));
            LOGGER.warn(
                    "[RSI-API] external operation {} marked {}: {}",
                    entry.operationId(), state, detail);
        }

        private long gameTimeFor(
                ExternalOperationJournal.Entry entry
        ) {
            return Math.max(
                    entry.updatedGameTime(), entry.updatedGameTime() + 1L);
        }

        private void clear() {
            pending.clear();
            queued.clear();
            drivers.clear();
        }
    }

    private record RecoveryContext(
            ServerLevel level,
            RecipeExecutionIntegration execution,
            MaterialsView materials,
            OperationContext operationContext
    ) {
    }

    private record MaterialsView(
            List<ReservedMaterial> materials
    ) implements ReservedMaterialView {
        private MaterialsView {
            materials = materials == null
                    ? List.of() : List.copyOf(materials);
        }

        @Override
        public Optional<ReservedMaterial> find(String entryId) {
            return materials.stream()
                    .filter(material ->
                            material.entryId().equals(entryId))
                    .findFirst();
        }
    }

    private static final class Driver {
        private ExternalOperationJournal.Entry entry;
        private final ExecutionPlan plan;
        private final MaterialsView materials;
        private final RunningOperation operation;
        private final Map<ResourceLocation, Long> outputTotals =
                new LinkedHashMap<>();
        private final Map<String, Long> materialTotals =
                new LinkedHashMap<>();
        private long lastStateRevision = -1L;

        private Driver(
                ExternalOperationJournal.Entry entry,
                ExecutionPlan plan,
                MaterialsView materials,
                RunningOperation operation
        ) {
            this.entry = entry;
            this.plan = plan;
            this.materials = materials;
            this.operation = operation;
            rebuildTotals(entry.collectedOutputs(),
                    entry.collectedDispositions());
        }

        private void tick(
                MinecraftServer server,
                Runtime runtime
        ) {
            ServerLevel level = server.getLevel(ResourceKey.create(
                    Registries.DIMENSION, entry.dimension()));
            ServerPlayer player = server.getPlayerList()
                    .getPlayer(entry.ownerId());
            if (level == null || player == null
                    || player.hasDisconnected()) {
                return;
            }
            CraftObservation observation;
            try {
                observation = operation.observe(level);
            } catch (RuntimeException | LinkageError failure) {
                cleanup(level, player, runtime,
                        "observe exception: "
                                + failure.getClass().getName());
                return;
            }
            if (observation == null) {
                cleanup(level, player, runtime,
                        "observe returned null");
                return;
            }
            if (lastStateRevision >= 0L
                    && observation.stateRevision()
                    < lastStateRevision) {
                cleanup(level, player, runtime,
                        "state revision moved backwards");
                return;
            }
            lastStateRevision = observation.stateRevision();
            switch (observation.phase()) {
                case READY_TO_COLLECT, COLLECTING -> {
                    String failure;
                    try {
                        failure = collect(runtime, level, player);
                    } catch (RuntimeException | LinkageError exception) {
                        failure = "collection exception: "
                                + exception.getClass().getName();
                    }
                    if (failure != null) {
                        cleanup(level, player, runtime, failure);
                        return;
                    }
                    if (entry.collectionComplete()) {
                        finish(server, player, runtime);
                    }
                }
                case SETTLING, SUCCEEDED -> {
                    if (!entry.collectionComplete()) {
                        cleanup(level, player, runtime,
                                "operation succeeded before collection completed");
                        return;
                    }
                    finish(server, player, runtime);
                }
                case FAILED, CANCELLED, CANCELLING ->
                        cleanup(level, player, runtime,
                                "resumed operation reported "
                                        + observation.phase());
                case QUEUED, PREPARING, WAITING_FOR_MACHINE, WORKING ->
                        persistActive(
                                runtime.journal, level, "");
            }
        }

        @Nullable
        private String collect(
                Runtime runtime,
                ServerLevel level,
                ServerPlayer player
        ) {
            OperationCollection collection;
            try {
                collection = operation.collect(player);
            } catch (RuntimeException | LinkageError failure) {
                return "collect exception: "
                        + failure.getClass().getName();
            }
            long previousSequence = entry.snapshot() == null
                    ? 0L : entry.snapshot().collectionSequence();
            if (collection.collectionSequence() < previousSequence) {
                return "collection sequence moved backwards";
            }
            if (collection.collectionSequence() == previousSequence) {
                return null;
            }
            List<CollectedOutput> outputs =
                    new ArrayList<>(entry.collectedOutputs());
            List<MaterialDisposition> dispositions =
                    new ArrayList<>(entry.collectedDispositions());
            Map<ResourceLocation, Long> nextOutputs =
                    new LinkedHashMap<>(outputTotals);
            Map<String, Long> nextMaterials =
                    new LinkedHashMap<>(materialTotals);
            Map<ResourceLocation, OutputPort> ports =
                    new LinkedHashMap<>();
            for (OutputPort port : plan.outputPorts()) {
                ports.put(port.portId(), port);
            }
            for (CollectedOutput output : collection.outputs()) {
                OutputPort declared = ports.get(output.portId());
                if (declared == null) {
                    return "undeclared custom output "
                            + output.portId();
                }
                long previous = nextOutputs.getOrDefault(
                        output.portId(), 0L);
                String failure =
                        ExternalIntegrationBatchDelegate
                                .validateCollectedOutputAgainstPort(
                                        declared, output, previous);
                if (failure != null) return failure;
                nextOutputs.put(
                        output.portId(),
                        Math.addExact(previous, output.amount()));
                outputs.add(output);
            }
            for (MaterialDisposition disposition
                    : collection.materials()) {
                ReservedMaterial reserved = materials.find(
                        disposition.material().entryId()).orElse(null);
                if (reserved == null) {
                    return "undeclared material "
                            + disposition.material().entryId();
                }
                long previous = nextMaterials.getOrDefault(
                        reserved.entryId(), 0L);
                String failure =
                        ExternalIntegrationBatchDelegate
                                .validateMaterialDispositionAgainstReservation(
                                        reserved, disposition, previous);
                if (failure != null) return failure;
                nextMaterials.put(
                        reserved.entryId(),
                        Math.addExact(
                                previous,
                                disposition.material().amount()));
                dispositions.add(disposition);
            }
            if (collection.complete()) {
                String completionFailure = validateCompletion(
                        nextOutputs, nextMaterials, dispositions);
                if (completionFailure != null) {
                    return completionFailure;
                }
            }
            outputTotals.clear();
            outputTotals.putAll(nextOutputs);
            materialTotals.clear();
            materialTotals.putAll(nextMaterials);
            OperationSnapshot snapshot = operation.snapshot();
            entry = entry.withCollection(
                    outputs,
                    dispositions,
                    collection.complete(),
                    snapshot,
                    level.getGameTime()
            );
            runtime.journal.put(entry);
            return null;
        }

        private String validateCompletion(
                Map<ResourceLocation, Long> outputs,
                Map<String, Long> materialTotals,
                List<MaterialDisposition> dispositions
        ) {
            for (OutputPort port : plan.outputPorts()) {
                long total = outputs.getOrDefault(
                        port.portId(), 0L);
                if (port.probability().mode()
                        == OutputProbabilityMode.GUARANTEED
                        && total < port.amount().min()) {
                    return "custom output below guaranteed minimum: "
                            + port.portId();
                }
            }
            for (ReservedMaterial material : materials.materials()) {
                if (materialTotals.getOrDefault(
                        material.entryId(), 0L)
                        != material.amount()) {
                    return "reserved material not fully accounted: "
                            + material.entryId();
                }
            }
            if (dispositions.stream().anyMatch(disposition ->
                    disposition.outcome() == MaterialOutcome.UNCERTAIN)) {
                return "collection contains uncertain materials";
            }
            return null;
        }

        private void finish(
                MinecraftServer server,
                ServerPlayer player,
                Runtime runtime
        ) {
            try {
                runtime.journal.put(entry.withState(
                        ExternalOperationJournal.State.DELIVERING,
                        server.overworld().getGameTime(),
                        "delivering recovered outputs"
                ));
                deliverOutputs(server, player);
                operation.onSettled(player);
                runtime.journal.put(entry.withState(
                        ExternalOperationJournal.State.COMPLETED,
                        server.overworld().getGameTime(),
                        "recovered and settled"
                ));
                runtime.drivers.remove(entry.operationId());
            } catch (RuntimeException | LinkageError failure) {
                LOGGER.warn(
                        "[RSI-API] recovered output delivery failed for {}",
                        entry.operationId(),
                        failure
                );
                runtime.journal.put(entry.withState(
                        ExternalOperationJournal.State.UNCERTAIN,
                        server.overworld().getGameTime(),
                        "output delivery exception: "
                                + failure.getClass().getName()
                ));
                runtime.drivers.remove(entry.operationId());
            }
        }

        private void deliverOutputs(
                MinecraftServer server,
                ServerPlayer player
        ) {
            for (CollectedOutput output : entry.collectedOutputs()) {
                if (output.resource().kind()
                        == IntegrationResourceKey.ResourceKind.ITEM) {
                    deliverItem(player, output);
                } else {
                    deliverFluid(server, output);
                }
            }
        }

        private void deliverItem(
                ServerPlayer player,
                CollectedOutput output
        ) {
            long remaining = output.amount();
            while (remaining > 0L) {
                int chunk = (int) Math.min(
                        Integer.MAX_VALUE, remaining);
                ItemStack stack =
                        ExternalRecipeProjection.toStack(
                                output.resource(), chunk);
                ItemStack uninserted =
                        PlayerUtils.insertIntoPlayerInventory(
                                player, stack);
                if (!uninserted.isEmpty()) {
                    CraftItemRecovery.get(player.server)
                            .retain(entry.ownerId(), uninserted);
                }
                remaining -= chunk;
            }
        }

        private void deliverFluid(
                MinecraftServer server,
                CollectedOutput output
        ) {
            long remaining = output.amount();
            while (remaining > 0L) {
                int chunk = (int) Math.min(
                        Integer.MAX_VALUE, remaining);
                CompoundTag fluidTag = new CompoundTag();
                fluidTag.putString(
                        "FluidName",
                        output.resource().id().toString());
                fluidTag.putInt("Amount", chunk);
                CompoundTag identity =
                        output.resource().identity();
                if (!identity.isEmpty()) {
                    fluidTag.put("Tag", identity);
                }
                UnifiedGridFluidRecovery.get(server).retainRaw(
                        entry.ownerId(), fluidTag, null);
                remaining -= chunk;
            }
        }

        private void cleanup(
                ServerLevel level,
                ServerPlayer player,
                Runtime runtime,
                String detail
        ) {
            try {
                OperationCleanupResult cleanup =
                        operation.onFailure(
                                FailureReason.EXECUTION_EXCEPTION,
                                player
                        );
                String failure =
                        ExternalIntegrationBatchDelegate
                                .validateCustomCleanup(
                                        cleanup, materials);
                if (failure == null) {
                    runtime.journal.put(entry.withState(
                            ExternalOperationJournal.State.DELIVERING,
                            level.getGameTime(),
                            "delivering recovered materials"
                    ));
                    deliverRecoveredMaterials(
                            player, cleanup);
                    operation.onSettled(player);
                    runtime.journal.put(entry.withState(
                            ExternalOperationJournal.State.FAILED,
                            level.getGameTime(),
                            detail
                    ));
                } else {
                    runtime.journal.put(entry.withState(
                            ExternalOperationJournal.State.UNCERTAIN,
                            level.getGameTime(),
                            detail + "; " + failure
                    ));
                }
            } catch (RuntimeException | LinkageError exception) {
                runtime.journal.put(entry.withState(
                        ExternalOperationJournal.State.UNCERTAIN,
                        level.getGameTime(),
                        detail + "; cleanup exception: "
                                + exception.getClass().getName()
                ));
            }
            runtime.drivers.remove(entry.operationId());
        }

        private void deliverRecoveredMaterials(
                ServerPlayer player,
                OperationCleanupResult cleanup
        ) {
            for (MaterialDisposition disposition
                    : cleanup.materials()) {
                if (disposition.outcome()
                        != MaterialOutcome.RECOVERED) {
                    continue;
                }
                ReservedMaterial material = disposition.material();
                if (material.resource().kind()
                        == IntegrationResourceKey.ResourceKind.ITEM) {
                    deliverItem(
                            player,
                            new CollectedOutput(
                                    material.resource(),
                                    material.amount(),
                                    OutputKind.PRIMARY,
                                    materialEntryPort(material)
                            )
                    );
                } else {
                    deliverFluid(
                            player.server,
                            new CollectedOutput(
                                    material.resource(),
                                    material.amount(),
                                    OutputKind.PRIMARY,
                                    materialEntryPort(material)
                            )
                    );
                }
            }
            for (CollectedOutput output
                    : cleanup.recoveredOutputs()) {
                if (output.resource().kind()
                        == IntegrationResourceKey.ResourceKind.ITEM) {
                    deliverItem(player, output);
                } else {
                    deliverFluid(player.server, output);
                }
            }
        }

        private ResourceLocation materialEntryPort(
                ReservedMaterial material
        ) {
            ResourceLocation port = ResourceLocation.tryParse(
                    material.entryId());
            return port == null ? entry.recipeId() : port;
        }

        private void persistActive(
                ExternalOperationJournal journal,
                ServerLevel level,
                String detail
        ) {
            OperationSnapshot snapshot;
            try {
                snapshot = operation.snapshot();
            } catch (RuntimeException | LinkageError failure) {
                snapshot = entry.snapshot();
            }
            entry = entry.withSnapshot(
                    snapshot, level.getGameTime())
                    .withState(
                            ExternalOperationJournal.State.ACTIVE,
                            level.getGameTime(),
                            detail
                    );
            journal.put(entry);
        }

        private void rebuildTotals(
                List<CollectedOutput> outputs,
                List<MaterialDisposition> dispositions
        ) {
            for (CollectedOutput output : outputs) {
                outputTotals.merge(
                        output.portId(),
                        output.amount(),
                        Math::addExact
                );
            }
            for (MaterialDisposition disposition : dispositions) {
                materialTotals.merge(
                        disposition.material().entryId(),
                        disposition.material().amount(),
                        Math::addExact
                );
            }
        }
    }

    private static String describe(
            List<IntegrationDiagnostic> diagnostics
    ) {
        if (diagnostics == null || diagnostics.isEmpty()) {
            return "resume rejected without diagnostics";
        }
        return diagnostics.stream()
                .map(diagnostic -> diagnostic.code().name())
                .distinct()
                .reduce((first, second) ->
                        first + "," + second)
                .orElse("resume rejected");
    }
}
