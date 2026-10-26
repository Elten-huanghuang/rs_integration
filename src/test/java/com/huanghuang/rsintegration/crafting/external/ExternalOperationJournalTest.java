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
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.fluids.FluidStack;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ExternalOperationJournalTest extends BootstrapTest {
    @Test
    void journalRoundTripsPlanSnapshotOutputsAndDispositions() {
        UUID owner = UUID.randomUUID();
        UUID transaction = UUID.randomUUID();
        UUID operationId = UUID.randomUUID();
        ResourceLocation recipeId = resource("recipe");
        ResourceLocation machineId = resource("machine");
        ResourceLocation recipeIntegrationId =
                resource("recipe_integration");
        ResourceLocation machineIntegrationId =
                resource("machine_integration");
        ResourceLocation executionIntegrationId =
                resource("execution_integration");
        OutputPort output = new OutputPort(
                resource("output"),
                "outputs",
                IntegrationResourceKey.item(
                        new ItemStack(Items.STICK, 1)),
                new OutputAmount(1L, 2L),
                OutputKind.PRIMARY,
                OutputProbability.probabilistic(
                        0.5D, 2, "roll"),
                false,
                false,
                null
        );
        ExecutionPlan plan = new ExecutionPlan(
                operationId,
                recipeId,
                machineId,
                recipeIntegrationId,
                machineIntegrationId,
                executionIntegrationId,
                7L,
                9L,
                "v1:recipe",
                "v1:descriptor",
                List.of(new InputRoute(
                        "item", 2, "items", 4L)),
                List.of(new FluidRoute(
                        "fluid", 1, "fluids", 500L)),
                List.of(new EnergyRoute("forge:energy", 400L)),
                20,
                List.of(output),
                new SupportScope(
                        List.of(new BlockPos(1, 0, 0)),
                        List.of(new BlockPos(0, 1, 0)),
                        false
                ),
                new ConcurrencyCapabilities(
                        ConcurrencyCapabilities.MaterialOwnership.CHAIN_RESERVED,
                        ConcurrencyCapabilities.OutputOwnership.MACHINE_SLOT,
                        ConcurrencyCapabilities.CleanupContract.SEPARABLE_OFFLINE,
                        ConcurrencyCapabilities.SideEffects.MACHINE_LOCAL,
                        ConcurrencyCapabilities.PreparationContract.RETRY_SAFE
                )
        );
        IntegrationResourceKey diamond = IntegrationResourceKey.item(
                new ItemStack(Items.DIAMOND, 1));
        IntegrationResourceKey water = IntegrationResourceKey.fluid(
                new FluidStack(Fluids.WATER, 1));
        ReservedMaterial itemReservation = new ReservedMaterial(
                "item",
                diamond,
                4L,
                InputConsumption.durability(3)
        );
        ReservedMaterial fluidReservation = new ReservedMaterial(
                "fluid",
                water,
                500L,
                InputConsumption.fuelWithReturnedContainer(
                        200L, resource("output"))
        );
        OperationSnapshot snapshot = new OperationSnapshot(
                LifecyclePhase.WORKING,
                3L,
                snapshotState()
        );
        CollectedOutput collected = new CollectedOutput(
                IntegrationResourceKey.item(
                        new ItemStack(Items.STICK, 1)),
                1L,
                OutputKind.PRIMARY,
                output.portId()
        );
        MaterialDisposition disposition = new MaterialDisposition(
                itemReservation,
                MaterialOutcome.CONSUMED
        );
        ExternalOperationJournal.Entry entry =
                new ExternalOperationJournal.Entry(
                        owner,
                        transaction,
                        operationId,
                        2,
                        recipeId,
                        resource("overworld"),
                        new BlockPos(4, 64, -8),
                        recipeIntegrationId,
                        machineIntegrationId,
                        executionIntegrationId,
                        7L,
                        9L,
                        "v1:recipe",
                        "v1:descriptor",
                        plan,
                        List.of(itemReservation, fluidReservation),
                        ExternalOperationJournal.State.ACTIVE,
                        100L,
                        "working",
                        snapshot,
                        List.of(collected),
                        List.of(disposition),
                        false
                );

        ExternalOperationJournal journal =
                new ExternalOperationJournal();
        journal.put(entry);
        CompoundTag saved = journal.save(new CompoundTag());
        ExternalOperationJournal loaded =
                new ExternalOperationJournal(saved);

        assertEquals(entry, loaded.find(operationId));
        assertEquals(1, loaded.recoverableEntries().size());
        assertEquals(snapshot, loaded.find(operationId).snapshot());
    }

    @Test
    void markerOnlyFluidAndMissingIdentityStillRoundTrip() {
        IntegrationResourceKey missingFluid =
                new IntegrationResourceKey(
                        IntegrationResourceKey.ResourceKind.FLUID,
                        new ResourceLocation("missingmod", "not_registered"),
                        new CompoundTag()
                );
        ExternalOperationJournal.Entry entry = minimalEntry(
                missingFluid, ExternalOperationJournal.State.UNCERTAIN);
        ExternalOperationJournal journal =
                new ExternalOperationJournal();
        journal.put(entry);
        CompoundTag saved = journal.save(new CompoundTag());
        ExternalOperationJournal loaded =
                new ExternalOperationJournal(saved);

        ExternalOperationJournal.Entry restored =
                loaded.find(entry.operationId());
        assertEquals(missingFluid,
                restored.reservedMaterials().get(0).resource());
        assertEquals(ExternalOperationJournal.State.UNCERTAIN,
                restored.state());
        assertTrue(loaded.recoverableEntries().isEmpty());
    }

    @Test
    void recoveryLifecycleAcceptsAnEmptyJournal() {
        MinecraftServer server = mock(MinecraftServer.class);
        ServerLevel level = mock(ServerLevel.class);
        DimensionDataStorage storage =
                mock(DimensionDataStorage.class);
        ExternalOperationJournal journal =
                new ExternalOperationJournal();
        when(server.overworld()).thenReturn(level);
        when(level.getDataStorage()).thenReturn(storage);
        when(storage.computeIfAbsent(
                any(), any(), anyString())).thenReturn(journal);

        ExternalOperationRecoveryService.serverStarted(server);
        ExternalOperationRecoveryService.tick(server);
        ExternalOperationRecoveryService.serverStopping(server);
    }

    private static ExternalOperationJournal.Entry minimalEntry(
            IntegrationResourceKey resource,
            ExternalOperationJournal.State state
    ) {
        UUID owner = UUID.randomUUID();
        UUID transaction = UUID.randomUUID();
        UUID operationId = UUID.randomUUID();
        ResourceLocation recipeId = resource("recipe");
        ResourceLocation machineId = resource("machine");
        ResourceLocation recipeIntegrationId =
                resource("recipe_integration");
        ResourceLocation machineIntegrationId =
                resource("machine_integration");
        ResourceLocation executionIntegrationId =
                resource("execution_integration");
        ExecutionPlan plan = new ExecutionPlan(
                operationId,
                recipeId,
                machineId,
                recipeIntegrationId,
                machineIntegrationId,
                executionIntegrationId,
                1L,
                1L,
                "v1:recipe",
                "v1:descriptor",
                List.of(),
                List.of(),
                List.of(),
                0,
                List.of(),
                new SupportScope(List.of(), List.of(), false),
                ConcurrencyCapabilities.unknown()
        );
        return new ExternalOperationJournal.Entry(
                owner,
                transaction,
                operationId,
                1,
                recipeId,
                resource("overworld"),
                BlockPos.ZERO,
                recipeIntegrationId,
                machineIntegrationId,
                executionIntegrationId,
                1L,
                1L,
                "v1:recipe",
                "v1:descriptor",
                plan,
                List.of(new ReservedMaterial(
                        "fluid",
                        resource,
                        1_000L,
                        InputConsumption.consumed()
                )),
                state,
                1L,
                "",
                new OperationSnapshot(
                        LifecyclePhase.CANCELLING,
                        1L,
                        new CompoundTag()
                ),
                List.of(),
                List.of(),
                false
        );
    }

    private static CompoundTag snapshotState() {
        CompoundTag state = new CompoundTag();
        CompoundTag nested = new CompoundTag();
        nested.putString("phase", "working");
        state.put("machine", nested);
        return state;
    }

    private static ResourceLocation resource(String path) {
        return new ResourceLocation("test", path);
    }
}
