package com.huanghuang.rsintegration.mods.profoundcurse;

import com.example.profound_curse.block.CursedPedestalBlockEntity;
import com.example.profound_curse.block.QuartzDisplayStandBlockEntity;
import com.huanghuang.rsintegration.api.IntegrationResourceKey;
import com.huanghuang.rsintegration.api.diagnostic.IntegrationDiagnostic;
import com.huanghuang.rsintegration.api.diagnostic.IntegrationDiagnosticCode;
import com.huanghuang.rsintegration.api.diagnostic.IntegrationSeverity;
import com.huanghuang.rsintegration.api.execution.CancelReason;
import com.huanghuang.rsintegration.api.execution.CollectedOutput;
import com.huanghuang.rsintegration.api.execution.CraftObservation;
import com.huanghuang.rsintegration.api.execution.FailureReason;
import com.huanghuang.rsintegration.api.execution.LifecyclePhase;
import com.huanghuang.rsintegration.api.execution.MaterialDisposition;
import com.huanghuang.rsintegration.api.execution.MaterialOutcome;
import com.huanghuang.rsintegration.api.execution.OperationCleanupResult;
import com.huanghuang.rsintegration.api.execution.OperationCollection;
import com.huanghuang.rsintegration.api.execution.OperationSnapshot;
import com.huanghuang.rsintegration.api.execution.ReservedMaterial;
import com.huanghuang.rsintegration.api.execution.RunningOperation;
import com.huanghuang.rsintegration.api.machine.MachineContext;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 诅咒基座 100 tick 自定义合成操作。 */
final class CursedPedestalOperation implements RunningOperation {
    private final MachineContext machine;
    private final ResourceLocation recipeId;
    private final ResourceLocation outputPortId;
    private final IntegrationResourceKey output;
    private final List<ReservedMaterial> reservedMaterials;
    private final List<BlockPos> displayPositions;
    private final long startedAtTick;

    private long collectionSequence;
    private boolean complete;
    private boolean readyToCollect;
    @Nullable
    private OperationCleanupResult cleanupResult;

    CursedPedestalOperation(
            MachineContext machine,
            ResourceLocation recipeId,
            ResourceLocation outputPortId,
            IntegrationResourceKey output,
            List<ReservedMaterial> reservedMaterials,
            long startedAtTick
    ) {
        this.machine = machine;
        this.recipeId = recipeId;
        this.outputPortId = outputPortId;
        this.output = output;
        this.reservedMaterials = List.copyOf(reservedMaterials);
        this.displayPositions = CursedPedestalStructure.displayPositions(
                machine.position());
        this.startedAtTick = startedAtTick;
    }

    @Override
    public CraftObservation observe(ServerLevel level) {
        CursedPedestalBlockEntity pedestal =
                CursedPedestalStructure.pedestal(
                        level, machine.position());
        if (pedestal == null) {
            return failedObservation(
                    "machine_missing_during_observation");
        }
        if (matchesOutput(pedestal.getItem())) {
            readyToCollect = true;
            return observation(
                    LifecyclePhase.READY_TO_COLLECT,
                    CursedPedestalStructure.CRAFT_DURATION,
                    level.getGameTime()
            );
        }
        long elapsed = elapsedTicks(level);
        if (pedestal.isCrafting()) {
            return observation(
                    LifecyclePhase.WORKING,
                    (int) Math.min(
                            CursedPedestalStructure.CRAFT_DURATION - 1L,
                            elapsed
                    ),
                    level.getGameTime()
            );
        }
        if (elapsed >= CursedPedestalStructure.CRAFT_TIMEOUT_TICKS) {
            return failedObservation(
                    "pedestal_recipe_did_not_produce_output");
        }
        return observation(
                LifecyclePhase.WORKING,
                (int) Math.min(
                        CursedPedestalStructure.CRAFT_DURATION - 1L,
                        elapsed
                ),
                level.getGameTime()
        );
    }

    @Override
    public OperationCollection collect(ServerPlayer player) {
        collectionSequence++;
        if (complete) {
            return new OperationCollection(
                    collectionSequence,
                    List.of(),
                    List.of(),
                    true,
                    List.of()
            );
        }
        ServerLevel level = machine.level();
        CursedPedestalBlockEntity pedestal =
                CursedPedestalStructure.pedestal(
                        level, machine.position());
        if (pedestal == null) {
            return new OperationCollection(
                    collectionSequence,
                    List.of(),
                    List.of(),
                    false,
                    List.of(diagnostic(
                            IntegrationDiagnosticCode.OUTPUT_MISSING,
                            "machine_missing_during_collection"
                    ))
            );
        }
        if (!matchesOutput(pedestal.getItem())) {
            return new OperationCollection(
                    collectionSequence,
                    List.of(),
                    List.of(),
                    false,
                    List.of()
            );
        }
        ItemStack result = CursedPedestalStructure.removeStoredItem(
                level, machine.position(), pedestal);
        if (!matchesOutput(result)) {
            return new OperationCollection(
                    collectionSequence,
                    List.of(),
                    List.of(),
                    false,
                    List.of(diagnostic(
                            IntegrationDiagnosticCode.OUTPUT_IDENTITY_MISMATCH,
                            "pedestal_output_changed_during_collection"
                    ))
            );
        }
        complete = true;
        CursedPedestalStructure.refreshAllVisualStates(
                level, machine.position());
        return new OperationCollection(
                collectionSequence,
                List.of(CollectedOutput.primary(
                        IntegrationResourceKey.item(result),
                        result.getCount(),
                        outputPortId
                )),
                consumedMaterials(),
                true,
                List.of()
        );
    }

    @Override
    public OperationSnapshot snapshot() {
        CompoundTag state = new CompoundTag();
        state.putString("recipe", recipeId.toString());
        state.putString("output_port", outputPortId.toString());
        state.putLong("started_at", startedAtTick);
        state.put("output_identity", output.identity());

        ListTag positions = new ListTag();
        for (BlockPos pos : displayPositions) {
            positions.add(StringTag.valueOf(pos.toShortString()));
        }
        state.put("display_positions", positions);

        ListTag entries = new ListTag();
        for (ReservedMaterial material : reservedMaterials) {
            entries.add(StringTag.valueOf(material.entryId()));
        }
        state.put("materials", entries);
        return new OperationSnapshot(
                complete
                        ? LifecyclePhase.SUCCEEDED
                        : readyToCollect
                        ? LifecyclePhase.READY_TO_COLLECT
                        : LifecyclePhase.WORKING,
                collectionSequence,
                state
        );
    }

    @Override
    public OperationCleanupResult onFailure(
            FailureReason reason,
            @Nullable ServerPlayer player
    ) {
        return cleanup(player);
    }

    @Override
    public OperationCleanupResult onCancel(
            CancelReason reason,
            @Nullable ServerPlayer player
    ) {
        return cleanup(player);
    }

    @Override
    public void onSettled(@Nullable ServerPlayer player) {
        CursedPedestalStructure.refreshAllVisualStates(
                machine.level(), machine.position());
    }

    private OperationCleanupResult cleanup(
            @Nullable ServerPlayer player
    ) {
        if (cleanupResult != null) return cleanupResult;
        if (player == null) {
            cleanupResult = uncertainCleanup(
                    "cleanup_requires_online_player");
            return cleanupResult;
        }

        ServerLevel level = machine.level();
        CursedPedestalBlockEntity pedestal =
                CursedPedestalStructure.pedestal(
                        level, machine.position());
        if (pedestal == null) {
            cleanupResult = uncertainCleanup(
                    "machine_missing_during_cleanup");
            return cleanupResult;
        }

        ItemStack stored = pedestal.getItem();
        if (matchesOutput(stored)) {
            ItemStack recovered = CursedPedestalStructure.removeStoredItem(
                    level, machine.position(), pedestal);
            CursedPedestalStructure.refreshAllVisualStates(
                    level, machine.position());
            cleanupResult = new OperationCleanupResult(
                    true,
                    consumedMaterials(),
                    matchesOutput(recovered)
                            ? List.of(CollectedOutput.primary(
                            IntegrationResourceKey.item(recovered),
                            recovered.getCount(),
                            outputPortId
                    ))
                            : List.of(),
                    List.of()
            );
            return cleanupResult;
        }

        List<ItemStack> recoveredItems = new ArrayList<>();
        addIfPresent(recoveredItems, takeIfReserved(
                level, machine.position(), pedestal));
        for (BlockPos pos : displayPositions) {
            QuartzDisplayStandBlockEntity stand =
                    CursedPedestalStructure.displayStand(level, pos);
            if (stand == null) continue;
            addIfPresent(recoveredItems,
                    takeIfReserved(level, pos, stand));
        }
        CursedPedestalStructure.refreshAllVisualStates(
                level, machine.position());

        List<MaterialDisposition> dispositions =
                reconcileRecoveredMaterials(recoveredItems);
        boolean completeCleanup = dispositions.stream()
                .noneMatch(disposition ->
                        disposition.outcome() == MaterialOutcome.UNCERTAIN);
        cleanupResult = new OperationCleanupResult(
                completeCleanup,
                dispositions,
                List.of(),
                completeCleanup
                        ? List.of()
                        : List.of(diagnostic(
                        IntegrationDiagnosticCode.EXECUTION_CLEANUP_UNCERTAIN,
                        "placed_pedestal_materials_could_not_all_be_recovered"
                ))
        );
        return cleanupResult;
    }

    private ItemStack takeIfReserved(
            ServerLevel level,
            BlockPos pos,
            CursedPedestalBlockEntity holder
    ) {
        ItemStack stored = holder.getItem();
        if (stored.isEmpty()
                || !isReservedResource(
                IntegrationResourceKey.item(stored))) {
            return ItemStack.EMPTY;
        }
        return CursedPedestalStructure.removeStoredItem(
                level, pos, holder);
    }

    private boolean isReservedResource(
            IntegrationResourceKey resource
    ) {
        return reservedMaterials.stream().anyMatch(material ->
                material.resource().equals(resource));
    }

    private List<MaterialDisposition> reconcileRecoveredMaterials(
            List<ItemStack> recoveredItems
    ) {
        List<ItemStack> remaining = new ArrayList<>(recoveredItems);
        List<MaterialDisposition> dispositions = new ArrayList<>();
        for (ReservedMaterial material : reservedMaterials) {
            int match = matchingIndex(remaining, material.resource());
            if (match >= 0) {
                remaining.remove(match);
                dispositions.add(new MaterialDisposition(
                        material,
                        MaterialOutcome.RECOVERED
                ));
            } else {
                dispositions.add(new MaterialDisposition(
                        material,
                        MaterialOutcome.UNCERTAIN
                ));
            }
        }
        return List.copyOf(dispositions);
    }

    private OperationCleanupResult uncertainCleanup(String reason) {
        List<MaterialDisposition> dispositions = reservedMaterials.stream()
                .map(material -> new MaterialDisposition(
                        material,
                        MaterialOutcome.UNCERTAIN
                ))
                .toList();
        return new OperationCleanupResult(
                false,
                dispositions,
                List.of(),
                List.of(diagnostic(
                        IntegrationDiagnosticCode.EXECUTION_CLEANUP_UNCERTAIN,
                        reason
                ))
        );
    }

    private List<MaterialDisposition> consumedMaterials() {
        return reservedMaterials.stream()
                .map(material -> new MaterialDisposition(
                        material,
                        MaterialOutcome.CONSUMED
                ))
                .toList();
    }

    private long elapsedTicks(ServerLevel level) {
        return Math.max(0L, level.getGameTime() - startedAtTick);
    }

    private CraftObservation observation(
            LifecyclePhase phase,
            int completedTicks,
            long revision
    ) {
        return new CraftObservation(
                phase,
                completedTicks,
                CursedPedestalStructure.CRAFT_DURATION,
                Math.max(1L, revision),
                phase == LifecyclePhase.WORKING ? recipeId : null,
                List.of()
        );
    }

    private CraftObservation failedObservation(String reason) {
        return new CraftObservation(
                LifecyclePhase.FAILED,
                0,
                CursedPedestalStructure.CRAFT_DURATION,
                Math.max(1L, startedAtTick + CursedPedestalStructure.CRAFT_TIMEOUT_TICKS),
                recipeId,
                List.of(diagnostic(
                        IntegrationDiagnosticCode.EXECUTION_OBSERVATION_FAILED,
                        reason
                ))
        );
    }

    private boolean matchesOutput(ItemStack stack) {
        return !stack.isEmpty()
                && IntegrationResourceKey.item(stack).equals(output);
    }

    private static int matchingIndex(
            List<ItemStack> stacks,
            IntegrationResourceKey expected
    ) {
        for (int index = 0; index < stacks.size(); index++) {
            ItemStack stack = stacks.get(index);
            if (!stack.isEmpty()
                    && IntegrationResourceKey.item(stack)
                    .equals(expected)) {
                return index;
            }
        }
        return -1;
    }

    private static void addIfPresent(
            List<ItemStack> target,
            ItemStack stack
    ) {
        if (!stack.isEmpty()) target.add(stack.copy());
    }

    private static IntegrationDiagnostic diagnostic(
            IntegrationDiagnosticCode code,
            String reason
    ) {
        return new IntegrationDiagnostic(
                code,
                IntegrationSeverity.ERROR,
                ProfoundCurseApiIntegration.EXECUTION_INTEGRATION_ID,
                Map.of("reason", reason)
        );
    }
}
