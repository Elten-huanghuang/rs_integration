package com.huanghuang.rsintegration.crafting.external;

import com.huanghuang.rsintegration.api.IntegrationResourceKey;
import com.huanghuang.rsintegration.api.execution.CollectedOutput;
import com.huanghuang.rsintegration.api.execution.EnergyRoute;
import com.huanghuang.rsintegration.api.execution.ExecutionPlan;
import com.huanghuang.rsintegration.api.execution.FluidRoute;
import com.huanghuang.rsintegration.api.execution.InputRoute;
import com.huanghuang.rsintegration.api.execution.LifecyclePhase;
import com.huanghuang.rsintegration.api.execution.MaterialDisposition;
import com.huanghuang.rsintegration.api.execution.MaterialOutcome;
import com.huanghuang.rsintegration.api.execution.OperationSnapshot;
import com.huanghuang.rsintegration.api.execution.OutputPort;
import com.huanghuang.rsintegration.api.execution.ReservedMaterial;
import com.huanghuang.rsintegration.api.machine.ConcurrencyCapabilities;
import com.huanghuang.rsintegration.api.machine.SupportScope;
import com.huanghuang.rsintegration.api.recipe.InputConsumption;
import com.huanghuang.rsintegration.api.recipe.OutputAmount;
import com.huanghuang.rsintegration.api.recipe.OutputKind;
import com.huanghuang.rsintegration.api.recipe.OutputProbability;
import com.huanghuang.rsintegration.api.recipe.OutputProbabilityMode;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 公共自定义执行的跨重启 journal。
 *
 * <p>这里只保存能够重新解析和校验的身份、计划与快照，不保存世界对象或能力实例。</p>
 */
final class ExternalOperationJournal extends SavedData {
    private static final String DATA_NAME =
            "rs_integration_external_operations";
    private static final int FORMAT_VERSION = 1;
    private static final int MAX_TERMINAL_ENTRIES = 512;

    enum State {
        PRE_START,
        ACTIVE,
        DELIVERING,
        COMPLETED,
        FAILED,
        UNCERTAIN
    }

    record Entry(
            UUID ownerId,
            UUID transactionId,
            UUID operationId,
            int attempt,
            ResourceLocation recipeId,
            ResourceLocation dimension,
            BlockPos machinePos,
            ResourceLocation recipeIntegrationId,
            ResourceLocation machineIntegrationId,
            ResourceLocation executionIntegrationId,
            long recipeRevision,
            long descriptorRevision,
            String recipeFingerprint,
            String descriptorFingerprint,
            ExecutionPlan plan,
            List<ReservedMaterial> reservedMaterials,
            State state,
            long updatedGameTime,
            String detail,
            @Nullable OperationSnapshot snapshot,
            List<CollectedOutput> collectedOutputs,
            List<MaterialDisposition> collectedDispositions,
            boolean collectionComplete
    ) {
        Entry {
            Objects.requireNonNull(ownerId, "ownerId");
            Objects.requireNonNull(transactionId, "transactionId");
            Objects.requireNonNull(operationId, "operationId");
            Objects.requireNonNull(recipeId, "recipeId");
            Objects.requireNonNull(dimension, "dimension");
            Objects.requireNonNull(machinePos, "machinePos");
            Objects.requireNonNull(
                    recipeIntegrationId, "recipeIntegrationId");
            Objects.requireNonNull(
                    machineIntegrationId, "machineIntegrationId");
            Objects.requireNonNull(
                    executionIntegrationId, "executionIntegrationId");
            Objects.requireNonNull(recipeFingerprint, "recipeFingerprint");
            Objects.requireNonNull(
                    descriptorFingerprint, "descriptorFingerprint");
            Objects.requireNonNull(plan, "plan");
            Objects.requireNonNull(state, "state");
            if (attempt < 1 || recipeRevision < 0L
                    || descriptorRevision < 0L
                    || recipeFingerprint.isBlank()
                    || descriptorFingerprint.isBlank()
                    || updatedGameTime < 0L) {
                throw new IllegalArgumentException(
                        "invalid external operation journal entry");
            }
            machinePos = machinePos.immutable();
            detail = detail == null ? "" : detail;
            reservedMaterials = reservedMaterials == null
                    ? List.of() : List.copyOf(reservedMaterials);
            collectedOutputs = collectedOutputs == null
                    ? List.of() : List.copyOf(collectedOutputs);
            collectedDispositions = collectedDispositions == null
                    ? List.of() : List.copyOf(collectedDispositions);
        }

        boolean recoverable() {
            return state == State.PRE_START || state == State.ACTIVE;
        }

        Entry withState(
                State nextState,
                long gameTime,
                String nextDetail
        ) {
            return new Entry(
                    ownerId, transactionId, operationId, attempt,
                    recipeId, dimension, machinePos,
                    recipeIntegrationId, machineIntegrationId,
                    executionIntegrationId,
                    recipeRevision, descriptorRevision,
                    recipeFingerprint, descriptorFingerprint,
                    plan, reservedMaterials, nextState, gameTime,
                    nextDetail, snapshot, collectedOutputs,
                    collectedDispositions, collectionComplete
            );
        }

        Entry withSnapshot(
                @Nullable OperationSnapshot nextSnapshot,
                long gameTime
        ) {
            return new Entry(
                    ownerId, transactionId, operationId, attempt,
                    recipeId, dimension, machinePos,
                    recipeIntegrationId, machineIntegrationId,
                    executionIntegrationId,
                    recipeRevision, descriptorRevision,
                    recipeFingerprint, descriptorFingerprint,
                    plan, reservedMaterials, state, gameTime, detail,
                    nextSnapshot, collectedOutputs,
                    collectedDispositions, collectionComplete
            );
        }

        Entry withCollection(
                List<CollectedOutput> outputs,
                List<MaterialDisposition> dispositions,
                boolean complete,
                @Nullable OperationSnapshot nextSnapshot,
                long gameTime
        ) {
            return new Entry(
                    ownerId, transactionId, operationId, attempt,
                    recipeId, dimension, machinePos,
                    recipeIntegrationId, machineIntegrationId,
                    executionIntegrationId,
                    recipeRevision, descriptorRevision,
                    recipeFingerprint, descriptorFingerprint,
                    plan, reservedMaterials, State.ACTIVE, gameTime,
                    detail, nextSnapshot, outputs, dispositions, complete
            );
        }

        Entry withAttempt(int nextAttempt, long gameTime) {
            return new Entry(
                    ownerId, transactionId, operationId, nextAttempt,
                    recipeId, dimension, machinePos,
                    recipeIntegrationId, machineIntegrationId,
                    executionIntegrationId,
                    recipeRevision, descriptorRevision,
                    recipeFingerprint, descriptorFingerprint,
                    plan, reservedMaterials, state, gameTime, detail,
                    snapshot, collectedOutputs,
                    collectedDispositions, collectionComplete
            );
        }
    }

    private final Map<UUID, Entry> entries = new LinkedHashMap<>();

    ExternalOperationJournal() {
    }

    ExternalOperationJournal(CompoundTag tag) {
        if (tag.getInt("version") != FORMAT_VERSION) return;
        for (Tag value : tag.getList(
                "operations", Tag.TAG_COMPOUND)) {
            try {
                Entry entry = readEntry((CompoundTag) value);
                entries.put(entry.operationId(), entry);
            } catch (RuntimeException ignored) {
                // 单条损坏记录不能阻止其他操作恢复。
            }
        }
        trimTerminalEntries();
    }

    static ExternalOperationJournal get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                ExternalOperationJournal::new,
                ExternalOperationJournal::new,
                DATA_NAME
        );
    }

    synchronized void put(Entry entry) {
        entries.put(entry.operationId(), entry);
        trimTerminalEntries();
        setDirty();
    }

    synchronized void remove(UUID operationId) {
        if (entries.remove(operationId) != null) setDirty();
    }

    synchronized List<Entry> recoverableEntries() {
        return entries.values().stream()
                .filter(Entry::recoverable)
                .sorted(Comparator.comparingLong(Entry::updatedGameTime))
                .toList();
    }

    synchronized List<Entry> entries() {
        return List.copyOf(entries.values());
    }

    @Nullable
    synchronized Entry find(UUID operationId) {
        return entries.get(operationId);
    }

    private void trimTerminalEntries() {
        while (entries.values().stream()
                .filter(entry -> !entry.recoverable())
                .count() > MAX_TERMINAL_ENTRIES) {
            UUID removable = entries.values().stream()
                    .filter(entry -> !entry.recoverable())
                    .min(Comparator.comparingLong(Entry::updatedGameTime))
                    .map(Entry::operationId)
                    .orElse(null);
            if (removable == null) return;
            entries.remove(removable);
        }
    }

    @Override
    public synchronized CompoundTag save(CompoundTag tag) {
        tag.putInt("version", FORMAT_VERSION);
        ListTag operations = new ListTag();
        entries.values().stream()
                .map(ExternalOperationJournal::writeEntry)
                .forEach(operations::add);
        tag.put("operations", operations);
        return tag;
    }

    private static CompoundTag writeEntry(Entry entry) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("owner", entry.ownerId());
        tag.putUUID("transaction", entry.transactionId());
        tag.putUUID("operation", entry.operationId());
        tag.putInt("attempt", entry.attempt());
        tag.putString("recipe", entry.recipeId().toString());
        tag.putString("dimension", entry.dimension().toString());
        tag.put("machine_pos", writePos(entry.machinePos()));
        tag.putString(
                "recipe_integration",
                entry.recipeIntegrationId().toString());
        tag.putString(
                "machine_integration",
                entry.machineIntegrationId().toString());
        tag.putString(
                "execution_integration",
                entry.executionIntegrationId().toString());
        tag.putLong("recipe_revision", entry.recipeRevision());
        tag.putLong("descriptor_revision", entry.descriptorRevision());
        tag.putString(
                "recipe_fingerprint", entry.recipeFingerprint());
        tag.putString(
                "descriptor_fingerprint",
                entry.descriptorFingerprint());
        tag.putString("state", entry.state().name());
        tag.putLong("updated", entry.updatedGameTime());
        tag.putString("detail", entry.detail());
        tag.put("plan", writePlan(entry.plan()));
        tag.put(
                "reserved_materials",
                writeReservedMaterials(entry.reservedMaterials()));
        tag.put(
                "collected_outputs",
                writeCollectedOutputs(entry.collectedOutputs()));
        tag.put(
                "collected_dispositions",
                writeDispositions(entry.collectedDispositions()));
        tag.putBoolean(
                "collection_complete", entry.collectionComplete());
        if (entry.snapshot() != null) {
            tag.put("snapshot", writeSnapshot(entry.snapshot()));
        }
        return tag;
    }

    private static Entry readEntry(CompoundTag tag) {
        OperationSnapshot snapshot = tag.contains(
                "snapshot", Tag.TAG_COMPOUND)
                ? readSnapshot(tag.getCompound("snapshot"))
                : null;
        return new Entry(
                tag.getUUID("owner"),
                tag.getUUID("transaction"),
                tag.getUUID("operation"),
                tag.getInt("attempt"),
                readResourceLocation(tag.getString("recipe")),
                readResourceLocation(tag.getString("dimension")),
                readPos(tag.getCompound("machine_pos")),
                readResourceLocation(
                        tag.getString("recipe_integration")),
                readResourceLocation(
                        tag.getString("machine_integration")),
                readResourceLocation(
                        tag.getString("execution_integration")),
                tag.getLong("recipe_revision"),
                tag.getLong("descriptor_revision"),
                tag.getString("recipe_fingerprint"),
                tag.getString("descriptor_fingerprint"),
                readPlan(tag.getCompound("plan")),
                readReservedMaterials(
                        tag.getList(
                                "reserved_materials",
                                Tag.TAG_COMPOUND)),
                State.valueOf(tag.getString("state")),
                tag.getLong("updated"),
                tag.getString("detail"),
                snapshot,
                readCollectedOutputs(
                        tag.getList(
                                "collected_outputs",
                                Tag.TAG_COMPOUND)),
                readDispositions(
                        tag.getList(
                                "collected_dispositions",
                                Tag.TAG_COMPOUND)),
                tag.getBoolean("collection_complete")
        );
    }

    private static CompoundTag writePlan(ExecutionPlan plan) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("operation", plan.operationId());
        tag.putString("recipe", plan.recipeId().toString());
        tag.putString("machine", plan.machineId().toString());
        tag.putString(
                "recipe_integration",
                plan.recipeIntegrationId().toString());
        tag.putString(
                "machine_integration",
                plan.machineIntegrationId().toString());
        tag.putString(
                "execution_integration",
                plan.executionIntegrationId().toString());
        tag.putLong("recipe_revision", plan.recipeRevision());
        tag.putLong(
                "descriptor_revision", plan.descriptorRevision());
        tag.putString(
                "recipe_fingerprint", plan.recipeFingerprint());
        tag.putString(
                "descriptor_fingerprint",
                plan.descriptorFingerprint());
        tag.putInt("expected_ticks", plan.expectedTicks());
        tag.put("item_inputs", writeItemRoutes(plan.itemInputs()));
        tag.put("fluid_inputs", writeFluidRoutes(plan.fluidInputs()));
        tag.put("energy_inputs", writeEnergyRoutes(plan.energyInputs()));
        tag.put("output_ports", writeOutputPorts(plan.outputPorts()));
        tag.put("support_scope", writeSupportScope(plan.supportScope()));
        tag.put("concurrency", writeConcurrency(plan.concurrency()));
        return tag;
    }

    private static ExecutionPlan readPlan(CompoundTag tag) {
        return new ExecutionPlan(
                tag.getUUID("operation"),
                readResourceLocation(tag.getString("recipe")),
                readResourceLocation(tag.getString("machine")),
                readResourceLocation(
                        tag.getString("recipe_integration")),
                readResourceLocation(
                        tag.getString("machine_integration")),
                readResourceLocation(
                        tag.getString("execution_integration")),
                tag.getLong("recipe_revision"),
                tag.getLong("descriptor_revision"),
                tag.getString("recipe_fingerprint"),
                tag.getString("descriptor_fingerprint"),
                readItemRoutes(
                        tag.getList("item_inputs", Tag.TAG_COMPOUND)),
                readFluidRoutes(
                        tag.getList("fluid_inputs", Tag.TAG_COMPOUND)),
                readEnergyRoutes(
                        tag.getList("energy_inputs", Tag.TAG_COMPOUND)),
                tag.getInt("expected_ticks"),
                readOutputPorts(
                        tag.getList(
                                "output_ports", Tag.TAG_COMPOUND)),
                readSupportScope(tag.getCompound("support_scope")),
                readConcurrency(tag.getCompound("concurrency"))
        );
    }

    private static ListTag writeItemRoutes(List<InputRoute> routes) {
        ListTag result = new ListTag();
        for (InputRoute route : routes) {
            CompoundTag tag = new CompoundTag();
            tag.putString("entry", route.entryId());
            tag.putInt("slot", route.slot());
            tag.putString("group", route.inputGroup());
            tag.putLong("amount", route.amount());
            result.add(tag);
        }
        return result;
    }

    private static List<InputRoute> readItemRoutes(ListTag list) {
        List<InputRoute> result = new ArrayList<>(list.size());
        for (Tag value : list) {
            CompoundTag tag = (CompoundTag) value;
            result.add(new InputRoute(
                    tag.getString("entry"),
                    tag.getInt("slot"),
                    tag.getString("group"),
                    tag.getLong("amount")
            ));
        }
        return result;
    }

    private static ListTag writeFluidRoutes(List<FluidRoute> routes) {
        ListTag result = new ListTag();
        for (FluidRoute route : routes) {
            CompoundTag tag = new CompoundTag();
            tag.putString("entry", route.entryId());
            tag.putInt("tank", route.tank());
            tag.putString("group", route.inputGroup());
            tag.putLong("amount", route.amount());
            result.add(tag);
        }
        return result;
    }

    private static List<FluidRoute> readFluidRoutes(ListTag list) {
        List<FluidRoute> result = new ArrayList<>(list.size());
        for (Tag value : list) {
            CompoundTag tag = (CompoundTag) value;
            result.add(new FluidRoute(
                    tag.getString("entry"),
                    tag.getInt("tank"),
                    tag.getString("group"),
                    tag.getLong("amount")
            ));
        }
        return result;
    }

    private static ListTag writeEnergyRoutes(List<EnergyRoute> routes) {
        ListTag result = new ListTag();
        for (EnergyRoute route : routes) {
            CompoundTag tag = new CompoundTag();
            tag.putString("capability", route.capabilityId());
            tag.putLong("amount", route.amount());
            result.add(tag);
        }
        return result;
    }

    private static List<EnergyRoute> readEnergyRoutes(ListTag list) {
        List<EnergyRoute> result = new ArrayList<>(list.size());
        for (Tag value : list) {
            CompoundTag tag = (CompoundTag) value;
            result.add(new EnergyRoute(
                    tag.getString("capability"),
                    tag.getLong("amount")
            ));
        }
        return result;
    }

    private static ListTag writeOutputPorts(List<OutputPort> ports) {
        ListTag result = new ListTag();
        for (OutputPort port : ports) {
            CompoundTag tag = new CompoundTag();
            tag.putString("port", port.portId().toString());
            tag.putString("group", port.outputGroup());
            tag.put("resource", writeResource(port.resource()));
            tag.putLong("minimum", port.amount().min());
            tag.putLong("maximum", port.amount().max());
            tag.putString("kind", port.outputKind().name());
            tag.put("probability", writeProbability(port.probability()));
            tag.putBoolean("dynamic", port.dynamicIdentity());
            tag.putBoolean("target", port.targetProducing());
            if (port.sourceInputEntryId() != null) {
                tag.putString(
                        "source",
                        port.sourceInputEntryId().toString());
            }
            result.add(tag);
        }
        return result;
    }

    private static List<OutputPort> readOutputPorts(ListTag list) {
        List<OutputPort> result = new ArrayList<>(list.size());
        for (Tag value : list) {
            CompoundTag tag = (CompoundTag) value;
            result.add(new OutputPort(
                    readResourceLocation(tag.getString("port")),
                    tag.getString("group"),
                    readResource(tag.getCompound("resource")),
                    new OutputAmount(
                            tag.getLong("minimum"),
                            tag.getLong("maximum")
                    ),
                    OutputKind.valueOf(tag.getString("kind")),
                    readProbability(tag.getCompound("probability")),
                    tag.getBoolean("dynamic"),
                    tag.getBoolean("target"),
                    tag.contains("source", Tag.TAG_STRING)
                            ? readResourceLocation(
                            tag.getString("source"))
                            : null
            ));
        }
        return result;
    }

    private static CompoundTag writeProbability(
            OutputProbability probability
    ) {
        CompoundTag tag = new CompoundTag();
        tag.putString("mode", probability.mode().name());
        if (probability.chance() != null) {
            tag.putDouble("chance", probability.chance());
        }
        tag.putInt("attempts", probability.maxAttempts());
        tag.putString("group", probability.rollGroup());
        return tag;
    }

    private static OutputProbability readProbability(CompoundTag tag) {
        return new OutputProbability(
                OutputProbabilityMode.valueOf(tag.getString("mode")),
                tag.contains("chance", Tag.TAG_DOUBLE)
                        ? tag.getDouble("chance") : null,
                tag.getInt("attempts"),
                tag.getString("group")
        );
    }

    private static CompoundTag writeSupportScope(SupportScope scope) {
        CompoundTag tag = new CompoundTag();
        tag.put(
                "support_offsets",
                writePositions(scope.supportOffsets()));
        tag.put(
                "adjacent_blocks",
                writePositions(scope.adjacentBlocks()));
        tag.putBoolean("structural", scope.structural());
        return tag;
    }

    private static SupportScope readSupportScope(CompoundTag tag) {
        return new SupportScope(
                readPositions(tag.getList(
                        "support_offsets", Tag.TAG_COMPOUND)),
                readPositions(tag.getList(
                        "adjacent_blocks", Tag.TAG_COMPOUND)),
                tag.getBoolean("structural")
        );
    }

    private static ListTag writePositions(List<BlockPos> positions) {
        ListTag result = new ListTag();
        for (BlockPos position : positions) {
            result.add(writePos(position));
        }
        return result;
    }

    private static List<BlockPos> readPositions(ListTag list) {
        List<BlockPos> result = new ArrayList<>(list.size());
        for (Tag value : list) {
            result.add(readPos((CompoundTag) value));
        }
        return result;
    }

    private static CompoundTag writePos(BlockPos position) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("x", position.getX());
        tag.putInt("y", position.getY());
        tag.putInt("z", position.getZ());
        return tag;
    }

    private static BlockPos readPos(CompoundTag tag) {
        return new BlockPos(
                tag.getInt("x"), tag.getInt("y"), tag.getInt("z"));
    }

    private static CompoundTag writeConcurrency(
            ConcurrencyCapabilities concurrency
    ) {
        CompoundTag tag = new CompoundTag();
        tag.putString("materials", concurrency.materials().name());
        tag.putString(
                "output_ownership",
                concurrency.outputOwnership().name());
        tag.putString("cleanup", concurrency.cleanup().name());
        tag.putString(
                "side_effects", concurrency.sideEffects().name());
        tag.putString(
                "preparation", concurrency.preparation().name());
        return tag;
    }

    private static ConcurrencyCapabilities readConcurrency(
            CompoundTag tag
    ) {
        return new ConcurrencyCapabilities(
                ConcurrencyCapabilities.MaterialOwnership.valueOf(
                        tag.getString("materials")),
                ConcurrencyCapabilities.OutputOwnership.valueOf(
                        tag.getString("output_ownership")),
                ConcurrencyCapabilities.CleanupContract.valueOf(
                        tag.getString("cleanup")),
                ConcurrencyCapabilities.SideEffects.valueOf(
                        tag.getString("side_effects")),
                ConcurrencyCapabilities.PreparationContract.valueOf(
                        tag.getString("preparation"))
        );
    }

    private static ListTag writeReservedMaterials(
            List<ReservedMaterial> materials
    ) {
        ListTag result = new ListTag();
        for (ReservedMaterial material : materials) {
            CompoundTag tag = new CompoundTag();
            tag.putString("entry", material.entryId());
            tag.put("resource", writeResource(material.resource()));
            tag.putLong("amount", material.amount());
            tag.put("consumption", writeConsumption(material.consumption()));
            result.add(tag);
        }
        return result;
    }

    private static List<ReservedMaterial> readReservedMaterials(
            ListTag list
    ) {
        List<ReservedMaterial> result = new ArrayList<>(list.size());
        for (Tag value : list) {
            CompoundTag tag = (CompoundTag) value;
            result.add(new ReservedMaterial(
                    tag.getString("entry"),
                    readResource(tag.getCompound("resource")),
                    tag.getLong("amount"),
                    readConsumption(tag.getCompound("consumption"))
            ));
        }
        return result;
    }

    private static CompoundTag writeConsumption(
            InputConsumption consumption
    ) {
        CompoundTag tag = new CompoundTag();
        tag.putString("kind", consumption.kind().name());
        tag.putInt("damage", consumption.damagePerUse());
        tag.putLong(
                "fuel_ticks",
                consumption.fuelBurnTicksPerOperation());
        if (consumption.linkedOutputPortId() != null) {
            tag.putString(
                    "linked_output",
                    consumption.linkedOutputPortId().toString());
        }
        return tag;
    }

    private static InputConsumption readConsumption(CompoundTag tag) {
        return new InputConsumption(
                InputConsumption.Kind.valueOf(tag.getString("kind")),
                tag.getInt("damage"),
                tag.getLong("fuel_ticks"),
                tag.contains("linked_output", Tag.TAG_STRING)
                        ? readResourceLocation(
                        tag.getString("linked_output"))
                        : null
        );
    }

    private static ListTag writeCollectedOutputs(
            List<CollectedOutput> outputs
    ) {
        ListTag result = new ListTag();
        for (CollectedOutput output : outputs) {
            CompoundTag tag = new CompoundTag();
            tag.put("resource", writeResource(output.resource()));
            tag.putLong("amount", output.amount());
            tag.putString("kind", output.outputKind().name());
            tag.putString("port", output.portId().toString());
            result.add(tag);
        }
        return result;
    }

    private static List<CollectedOutput> readCollectedOutputs(
            ListTag list
    ) {
        List<CollectedOutput> result = new ArrayList<>(list.size());
        for (Tag value : list) {
            CompoundTag tag = (CompoundTag) value;
            result.add(new CollectedOutput(
                    readResource(tag.getCompound("resource")),
                    tag.getLong("amount"),
                    OutputKind.valueOf(tag.getString("kind")),
                    readResourceLocation(tag.getString("port"))
            ));
        }
        return result;
    }

    private static ListTag writeDispositions(
            List<MaterialDisposition> dispositions
    ) {
        ListTag result = new ListTag();
        for (MaterialDisposition disposition : dispositions) {
            CompoundTag tag = new CompoundTag();
            tag.put("material", writeReservedMaterial(
                    disposition.material()));
            tag.putString("outcome", disposition.outcome().name());
            result.add(tag);
        }
        return result;
    }

    private static List<MaterialDisposition> readDispositions(
            ListTag list
    ) {
        List<MaterialDisposition> result = new ArrayList<>(list.size());
        for (Tag value : list) {
            CompoundTag tag = (CompoundTag) value;
            result.add(new MaterialDisposition(
                    readReservedMaterial(tag.getCompound("material")),
                    MaterialOutcome.valueOf(tag.getString("outcome"))
            ));
        }
        return result;
    }

    private static CompoundTag writeReservedMaterial(
            ReservedMaterial material
    ) {
        CompoundTag tag = new CompoundTag();
        tag.putString("entry", material.entryId());
        tag.put("resource", writeResource(material.resource()));
        tag.putLong("amount", material.amount());
        tag.put("consumption", writeConsumption(material.consumption()));
        return tag;
    }

    private static ReservedMaterial readReservedMaterial(CompoundTag tag) {
        return new ReservedMaterial(
                tag.getString("entry"),
                readResource(tag.getCompound("resource")),
                tag.getLong("amount"),
                readConsumption(tag.getCompound("consumption"))
        );
    }

    private static CompoundTag writeSnapshot(OperationSnapshot snapshot) {
        CompoundTag tag = new CompoundTag();
        tag.putString("phase", snapshot.phase().name());
        tag.putLong("sequence", snapshot.collectionSequence());
        tag.put("state", snapshot.state());
        return tag;
    }

    private static OperationSnapshot readSnapshot(CompoundTag tag) {
        return new OperationSnapshot(
                LifecyclePhase.valueOf(tag.getString("phase")),
                tag.getLong("sequence"),
                tag.getCompound("state")
        );
    }

    private static CompoundTag writeResource(
            IntegrationResourceKey resource
    ) {
        CompoundTag tag = new CompoundTag();
        tag.putString("kind", resource.kind().name());
        tag.putString("id", resource.id().toString());
        tag.put("identity", resource.identity());
        return tag;
    }

    private static IntegrationResourceKey readResource(CompoundTag tag) {
        return new IntegrationResourceKey(
                IntegrationResourceKey.ResourceKind.valueOf(
                        tag.getString("kind")),
                readResourceLocation(tag.getString("id")),
                tag.getCompound("identity")
        );
    }

    private static ResourceLocation readResourceLocation(String value) {
        ResourceLocation id = ResourceLocation.tryParse(value);
        if (id == null) {
            throw new IllegalArgumentException(
                    "invalid resource location: " + value);
        }
        return id;
    }
}
