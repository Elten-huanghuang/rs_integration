package com.huanghuang.rsintegration.mods.embers;

import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.CraftStorageEndpoint;
import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.batch.IBatchDelegate.CraftPhase;
import com.huanghuang.rsintegration.crafting.batch.IBatchDelegate.PreparationResult;
import com.huanghuang.rsintegration.crafting.batch.IBatchDelegate.PreparationState;
import com.huanghuang.rsintegration.mods.ironsspellbooks.InkFluidSupport;
import com.huanghuang.rsintegration.recipe.ModRecipeHandlers;
import com.huanghuang.rsintegration.storage.StorageBackendId;
import com.huanghuang.rsintegration.storage.StorageOperationResult;
import com.huanghuang.rsintegration.storage.StorageOperationStatus;
import com.huanghuang.rsintegration.storage.StorageReference;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import com.huanghuang.rsintegration.util.ForcedChunkTicketManager;
import com.rekindled.embers.blockentity.MiniBoilerBlockEntity;
import com.rekindled.embers.recipe.BoilingRecipe;
import com.rekindled.embers.recipe.FluidHandlerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.fluids.capability.templates.FluidTank;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.Optional;

import static com.huanghuang.rsintegration.mods.embers.EmbersBoilingRecipeHandlerTest.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class EmbersMiniBoilerBatchDelegateTest extends BootstrapTest {
    @Test
    void batchUsesTankCapacitiesAndCanExceedTheGenericDispatchLimit() {
        Fixture fixture = new Fixture(1, 5);
        try (MockedStatic<InkFluidSupport> tokens = tokens()) {
            assertEquals(PreparationState.READY, fixture.prepare().state());
            assertTrue(fixture.delegate.expandsFlatBatchOperationLimit());
            assertEquals(200, fixture.delegate.flatBatchOperationLimit(32));
            assertEquals(200, fixture.delegate.prepareFlatBatch(1000));
            assertEquals(1000, fixture.delegate.getExpectedProduction().count());
            assertEquals(1, fixture.delegate.getRequiredMaterials().get(0).count());

            // 流体容量不受物品的 64 个堆叠上限限制。
            fixture.output.setCapacity(10000);
            assertEquals(1000, fixture.delegate.prepareFlatBatch(2000));
            assertEquals(5000, fixture.delegate.getExpectedProduction().count());
            assertEquals(0, fixture.delegate.prepareFlatBatch(0));
            assertThrows(IllegalArgumentException.class, () -> fixture.delegate.prepareGraphBatch(1001));
        }
    }

    @Test
    void bulkInputWaitsForTheWholeBatchAndCollectsGasOnce() {
        Fixture fixture = new Fixture(1, 5);
        try (MockedStatic<InkFluidSupport> tokens = tokens();
             MockedStatic<ForcedChunkTicketManager> tickets = mockStatic(ForcedChunkTicketManager.class)) {
            assertEquals(PreparationState.READY, fixture.prepare().state());
            assertEquals(200, fixture.delegate.prepareFlatBatch(1000));
            assertTrue(fixture.start(200));
            assertEquals(200, fixture.input.getFluidAmount());

            fixture.process(40);
            assertEquals(160, fixture.input.getFluidAmount());
            assertEquals(CraftPhase.WORKING, fixture.delegate.observeMachineCraft(fixture.level, fixture.machine).phase());
            assertTrue(fixture.delegate.collectAllResults(fixture.player).isEmpty());
            fixture.process(10000);
            assertTrue(fixture.input.isEmpty());
            assertEquals(CraftPhase.DONE, fixture.delegate.observeMachineCraft(fixture.level, fixture.machine).phase());
            assertEquals(1000, fixture.delegate.collectResult(fixture.player).getCount());
            assertTrue(fixture.delegate.collectAllResults(fixture.player).isEmpty());
            verify(fixture.machine, never()).boil(anyDouble());
        }
    }

    @Test
    void oneToOneRecipeCanFillTheEntireInputTank() {
        Fixture fixture = new Fixture(1, 1);
        try (MockedStatic<InkFluidSupport> tokens = tokens();
             MockedStatic<ForcedChunkTicketManager> tickets = mockStatic(ForcedChunkTicketManager.class)) {
            assertEquals(PreparationState.READY, fixture.prepare().state());
            assertEquals(1000, fixture.delegate.prepareFlatBatch(10000));
            assertTrue(fixture.start(1000));
            assertEquals(1000, fixture.input.getFluidAmount());
            fixture.process(10000);
            assertEquals(CraftPhase.DONE, fixture.delegate.observeMachineCraft(fixture.level, fixture.machine).phase());
            assertEquals(1000, fixture.delegate.collectResult(fixture.player).getCount());
        }
    }

    @Test
    void smallerReservationsAndNonUnitRecipesScaleExactlyOnce() {
        Fixture fixture = new Fixture(100, 500);
        try (MockedStatic<InkFluidSupport> tokens = tokens();
             MockedStatic<ForcedChunkTicketManager> tickets = mockStatic(ForcedChunkTicketManager.class)) {
            assertEquals(PreparationState.READY, fixture.prepare().state());
            assertEquals(2, fixture.delegate.prepareFlatBatch(100));
            assertEquals(1000, fixture.delegate.getExpectedProduction().count());
            assertEquals(1, fixture.delegate.prepareFlatBatch(1));
            assertEquals(500, fixture.delegate.getExpectedProduction().count());
            assertTrue(fixture.start());
            assertEquals(100, fixture.input.getFluidAmount());
            fixture.process(10000);
            assertEquals(CraftPhase.DONE, fixture.delegate.observeMachineCraft(fixture.level, fixture.machine).phase());
            assertEquals(500, fixture.delegate.collectResult(fixture.player).getCount());
        }
    }

    @Test
    void parallelWorkersUseExistingBatchSizingAndAggregatedMaterials() {
        Fixture fixture = new Fixture(1, 5);
        try (MockedStatic<InkFluidSupport> tokens = tokens();
             MockedStatic<ForcedChunkTicketManager> tickets = mockStatic(ForcedChunkTicketManager.class)) {
            assertEquals(PreparationState.READY, fixture.prepare().state());
            assertEquals(200, fixture.delegate.preferredParallelBatchSize(1200, 3));
            int executions = fixture.delegate.preferredParallelBatchSize(101, 3);
            assertEquals(34, executions);
            fixture.delegate.prepareGraphBatch(executions);
            assertEquals(170, fixture.delegate.getExpectedProduction().count());
            assertTrue(fixture.start(executions));
            assertEquals(34, fixture.input.getFluidAmount());
            fixture.process(10000);
            assertEquals(CraftPhase.DONE, fixture.delegate.observeMachineCraft(fixture.level, fixture.machine).phase());
            assertEquals(170, fixture.delegate.collectResult(fixture.player).getCount());
        }
    }

    @Test
    void reducedTankCapacityRejectsBulkStartAndRefundsEveryUnplacedMillibucket() {
        Fixture fixture = new Fixture(1, 5);
        try (MockedStatic<InkFluidSupport> tokens = tokens()) {
            assertEquals(PreparationState.READY, fixture.prepare().state());
            assertEquals(200, fixture.delegate.prepareFlatBatch(1000));
            fixture.output.setCapacity(500);
            assertFalse(fixture.start(200));
            fixture.delegate.clearMachineState(fixture.machine, fixture.player);
            assertEquals(200, fixture.delegate.failureRecoveredInputs().get(0).getCount());
            assertTrue(fixture.input.isEmpty());
        }
    }

    @Test
    void partialBatchCancellationRefundsOnlyTheStillUnconsumedInput() {
        Fixture fixture = new Fixture(1, 5);
        try (MockedStatic<InkFluidSupport> tokens = tokens();
             MockedStatic<ForcedChunkTicketManager> tickets = mockStatic(ForcedChunkTicketManager.class)) {
            assertEquals(PreparationState.READY, fixture.prepare().state());
            assertEquals(200, fixture.delegate.prepareFlatBatch(1000));
            assertTrue(fixture.start(200));
            fixture.process(40);
            fixture.delegate.clearMachineState(fixture.machine, fixture.player);
            assertEquals(160, fixture.delegate.failureRecoveredInputs().get(0).getCount());
            assertTrue(fixture.input.isEmpty());
            assertEquals(200, fixture.output.getFluidAmount());
            assertNull(fixture.machine.cachedRecipe);
        }
    }

    @Test
    void progressingBatchDoesNotHitTheSingleRecipeStallTimer() {
        Fixture fixture = new Fixture(1, 5);
        try (MockedStatic<InkFluidSupport> tokens = tokens();
             MockedStatic<ForcedChunkTicketManager> tickets = mockStatic(ForcedChunkTicketManager.class)) {
            assertEquals(PreparationState.READY, fixture.prepare().state());
            assertEquals(200, fixture.delegate.prepareFlatBatch(1000));
            assertTrue(fixture.start(200));
            fixture.process(50);
            when(fixture.level.getGameTime()).thenReturn(2401L);
            assertEquals(CraftPhase.WORKING, fixture.delegate.observeMachineCraft(fixture.level, fixture.machine).phase());
            fixture.process(50);
            when(fixture.level.getGameTime()).thenReturn(4800L);
            assertEquals(CraftPhase.WORKING, fixture.delegate.observeMachineCraft(fixture.level, fixture.machine).phase());
            when(fixture.level.getGameTime()).thenReturn(7201L);
            assertEquals(CraftPhase.FAILED, fixture.delegate.observeMachineCraft(fixture.level, fixture.machine).phase());
            fixture.delegate.clearMachineState(fixture.machine, fixture.player);
            assertEquals(100, fixture.delegate.failureRecoveredInputs().get(0).getCount());
            assertEquals(500, fixture.output.getFluidAmount());
        }
    }

    @Test
    void fillsColdBoilerAndCollectsOnlyPhysicallyProducedGas() {
        Fixture fixture = new Fixture(1, 5);
        try (MockedStatic<InkFluidSupport> tokens = tokens();
             MockedStatic<ForcedChunkTicketManager> tickets = mockStatic(ForcedChunkTicketManager.class)) {
            assertEquals(PreparationState.READY, fixture.prepare().state());
            assertTrue(fixture.start());
            assertEquals(1, fixture.input.getFluidAmount());
            assertSame(fixture.recipe, fixture.machine.cachedRecipe);
            assertEquals(CraftPhase.WORKING, fixture.delegate.observeMachineCraft(fixture.level, fixture.machine).phase());
            assertTrue(fixture.delegate.collectAllResults(fixture.player).isEmpty());
            verify(fixture.machine, never()).boil(anyDouble());

            // 原生余热事件负责触发 process；任务只观察真实的输入消耗和气体产物。
            FluidStack gas = fixture.recipe.process(new FluidHandlerContext(fixture.input), 1000);
            fixture.output.setFluid(gas);
            assertEquals(CraftPhase.DONE, fixture.delegate.observeMachineCraft(fixture.level, fixture.machine).phase());
            List<ItemStack> collected = fixture.delegate.collectAllResults(fixture.player);
            assertEquals(1, collected.size());
            assertEquals(5, InkFluidSupport.fluid(collected.get(0)).getAmount());
            assertTrue(fixture.output.isEmpty());
            assertTrue(fixture.delegate.collectAllResults(fixture.player).isEmpty());
        }
    }

    @Test
    void occupiedTanksAndMissingRsFluidStoragePreventReservation() {
        Fixture fixture = new Fixture(1, 5);
        try (MockedStatic<InkFluidSupport> tokens = tokens()) {
            fixture.input.setFluid(new FluidStack(Fluids.WATER, 3));
            assertEquals(PreparationState.RETRY, fixture.prepare().state());
            assertEquals(3, fixture.input.getFluidAmount());
            fixture.input.setFluid(FluidStack.EMPTY);
            fixture.output.setFluid(new FluidStack(Fluids.LAVA, 2));
            assertEquals(PreparationState.FATAL, fixture.prepare().state());
            assertEquals(2, fixture.output.getFluidAmount());
            fixture.output.setFluid(FluidStack.EMPTY);
            when(fixture.endpoint.session().reference()).thenReturn(new StorageReference(
                    new StorageBackendId("beyonddimensions"), "test"));
            assertEquals(PreparationState.FATAL, fixture.prepare().state());
            assertTrue(fixture.input.isEmpty());
        }
    }

    @Test
    void rejectsRecipeAmountsThatExceedEitherTank() {
        try (MockedStatic<InkFluidSupport> tokens = tokens()) {
            Fixture tooMuchInput = new Fixture(1001, 5);
            assertEquals(PreparationState.FATAL, tooMuchInput.prepare().state());
            Fixture tooMuchOutput = new Fixture(1, 1001);
            assertEquals(PreparationState.FATAL, tooMuchOutput.prepare().state());
        }
    }

    @Test
    void cancellationRecoversOnlyOwnUnconsumedFluid() {
        Fixture fixture = new Fixture(100, 500);
        try (MockedStatic<InkFluidSupport> tokens = tokens();
             MockedStatic<ForcedChunkTicketManager> tickets = mockStatic(ForcedChunkTicketManager.class)) {
            assertEquals(PreparationState.READY, fixture.prepare().state());
            assertTrue(fixture.start());
            fixture.input.setFluid(new FluidStack(Fluids.WATER, 120));
            fixture.delegate.clearMachineState(fixture.machine, fixture.player);
            assertEquals(100, fixture.delegate.failureRecoveredInputs().get(0).getCount());
            assertEquals(20, fixture.input.getFluidAmount());
            assertNull(fixture.machine.cachedRecipe);
        }
    }

    @Test
    void changedMachineBeforePlacementPreservesAllCommittedMaterialsForRefund() {
        Fixture fixture = new Fixture(100, 500);
        try (MockedStatic<InkFluidSupport> tokens = tokens()) {
            assertEquals(PreparationState.READY, fixture.prepare().state());
            fixture.input.setFluid(new FluidStack(Fluids.LAVA, 7));
            assertFalse(fixture.start());
            fixture.delegate.clearMachineState(fixture.machine, fixture.player);
            assertEquals(100, fixture.delegate.failureRecoveredInputs().get(0).getCount());
            assertEquals(7, fixture.input.getFluidAmount());
            assertEquals(Fluids.LAVA, fixture.input.getFluid().getFluid());
        }
    }

    @Test
    void missingHeatTimesOutWithAnActionableMessageAndRefundsInput() {
        Fixture fixture = new Fixture(100, 500);
        try (MockedStatic<InkFluidSupport> tokens = tokens();
             MockedStatic<ForcedChunkTicketManager> tickets = mockStatic(ForcedChunkTicketManager.class)) {
            assertEquals(PreparationState.READY, fixture.prepare().state());
            assertTrue(fixture.start());
            when(fixture.level.getGameTime()).thenReturn(2401L);
            var observation = fixture.delegate.observeMachineCraft(fixture.level, fixture.machine);
            assertEquals(CraftPhase.FAILED, observation.phase());
            assertEquals("rsi.embers_machine.error.boiler_stalled",
                    ((TranslatableContents) fixture.delegate.craftFailureMessage(observation).getContents()).getKey());
            assertFalse(fixture.delegate.failureConsumesInputs(observation));
            fixture.delegate.clearMachineState(fixture.machine, fixture.player);
            assertEquals(100, fixture.delegate.failureRecoveredInputs().get(0).getCount());
        }
    }

    @Test
    void externallyRemovedGasIsNotRecreatedOrRefundedAsInput() {
        Fixture fixture = new Fixture(1, 5);
        try (MockedStatic<InkFluidSupport> tokens = tokens();
             MockedStatic<ForcedChunkTicketManager> tickets = mockStatic(ForcedChunkTicketManager.class)) {
            assertEquals(PreparationState.READY, fixture.prepare().state());
            assertTrue(fixture.start());
            fixture.recipe.process(new FluidHandlerContext(fixture.input), 1);
            fixture.output.setFluid(new FluidStack(Fluids.LAVA, 2));
            var observation = fixture.delegate.observeMachineCraft(fixture.level, fixture.machine);
            assertEquals(CraftPhase.FAILED, observation.phase());
            assertTrue(fixture.delegate.failureConsumesInputs(observation));
            fixture.delegate.clearMachineState(fixture.machine, fixture.player);
            assertTrue(fixture.delegate.failureRecoveredInputs().isEmpty());
            assertEquals(2, fixture.output.getFluidAmount());
        }
    }

    private static final class Fixture {
        final EmbersMiniBoilerBatchDelegate delegate = new EmbersMiniBoilerBatchDelegate();
        final MiniBoilerBlockEntity machine = mock(MiniBoilerBlockEntity.class);
        final ServerLevel level = mock(ServerLevel.class);
        final ServerPlayer player = mock(ServerPlayer.class);
        final BlockPos pos = new BlockPos(0, 64, 0);
        final FluidTank input = new FluidTank(1000);
        final FluidTank output = new FluidTank(1000);
        final CraftStorageEndpoint endpoint = mock(CraftStorageEndpoint.class, RETURNS_DEEP_STUBS);
        final BoilingRecipe recipe;
        final int amount;

        Fixture(int amount, int produced) {
            this.amount = amount;
            recipe = new BoilingRecipe(new ResourceLocation("embers", "boiling/test"),
                    input(amount), new FluidStack(Fluids.LAVA, produced));
            when(machine.getBlockPos()).thenReturn(pos);
            when(machine.getFluidTank()).thenReturn(input);
            when(machine.getGasTank()).thenReturn(output);
            when(level.hasChunkAt(pos)).thenReturn(true);
            when(level.hasChunkAt(pos.above())).thenReturn(true);
            when(level.getBlockEntity(pos)).thenReturn(machine);
            when(level.registryAccess()).thenReturn(RegistryAccess.EMPTY);
            when(level.dimension()).thenReturn(Level.OVERWORLD);
            RecipeManager recipes = mock(RecipeManager.class);
            doReturn(Optional.of(recipe)).when(recipes).byKey(recipe.getId());
            when(level.getRecipeManager()).thenReturn(recipes);
            when(endpoint.session().reference()).thenReturn(new StorageReference(
                    new StorageBackendId("refinedstorage"), "test"));
            StorageOperationResult inserted = mock(StorageOperationResult.class);
            when(inserted.status()).thenReturn(StorageOperationStatus.SUCCESS);
            when(endpoint.insert(any(ServerPlayer.class), any(ItemStack.class), eq(true))).thenReturn(inserted);
            delegate.setStorageEndpoint(endpoint);
        }

        PreparationResult prepare() {
            try (MockedStatic<CraftPacketUtils> context = mockStatic(CraftPacketUtils.class);
                 MockedStatic<ModRecipeHandlers> handlers = mockStatic(ModRecipeHandlers.class)) {
                context.when(() -> CraftPacketUtils.resolveLevel(player.server, null, player)).thenReturn(level);
                handlers.when(() -> ModRecipeHandlers.handlerFor(recipe)).thenReturn(new EmbersBoilingRecipeHandler());
                return delegate.prepare(player, recipe.getId(), null, pos);
            }
        }

        boolean start() {
            return start(1);
        }

        boolean start(int executions) {
            return delegate.startPreparedOperation(player, List.of(token(new FluidStack(Fluids.WATER, amount * executions))),
                    new ExtractionLedger());
        }

        void process(int executions) {
            FluidStack gas = recipe.process(new FluidHandlerContext(input), executions);
            assertEquals(gas.getAmount(), output.fill(gas, IFluidHandler.FluidAction.EXECUTE));
        }
    }
}
