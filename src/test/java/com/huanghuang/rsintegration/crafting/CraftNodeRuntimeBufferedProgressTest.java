package com.huanghuang.rsintegration.crafting;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.crafting.CraftingResolver.StackKey;
import com.huanghuang.rsintegration.crafting.batch.BatchConcurrencyCapabilities;
import com.huanghuang.rsintegration.crafting.batch.IBatchDelegate;
import com.huanghuang.rsintegration.crafting.batch.OperationStartContext;
import com.huanghuang.rsintegration.crafting.batch.CountedOperationMaterials;
import com.huanghuang.rsintegration.crafting.batch.CountedOperationMaterials.Origin;
import com.huanghuang.rsintegration.crafting.batch.CountedOperationMaterials.Pool;
import com.huanghuang.rsintegration.crafting.batch.MaterialPlan;
import com.huanghuang.rsintegration.crafting.batch.InputBufferPlan;
import com.huanghuang.rsintegration.crafting.graph.ConcurrentNodeExecutor;
import com.huanghuang.rsintegration.crafting.graph.CaptureLeaseRegistry;
import com.huanghuang.rsintegration.crafting.graph.MachineLeaseRegistry;
import com.huanghuang.rsintegration.crafting.graph.NodeId;
import com.huanghuang.rsintegration.crafting.graph.DemandRole;
import com.huanghuang.rsintegration.crafting.graph.InputPortId;
import com.huanghuang.rsintegration.crafting.graph.MaterialKey;
import com.huanghuang.rsintegration.crafting.graph.MaterialBroker;
import com.huanghuang.rsintegration.crafting.graph.MaterialSource;
import com.huanghuang.rsintegration.crafting.graph.NodeOutputAccumulator;
import com.huanghuang.rsintegration.crafting.graph.OutputDeclaration;
import com.huanghuang.rsintegration.crafting.graph.OutputKind;
import com.huanghuang.rsintegration.crafting.graph.OutputPortId;
import com.huanghuang.rsintegration.crafting.graph.OutputProbability;
import com.huanghuang.rsintegration.crafting.graph.OperationBudget;
import com.huanghuang.rsintegration.crafting.graph.OperationId;
import com.huanghuang.rsintegration.crafting.loadbalancer.ParallelCraftGroup;
import com.huanghuang.rsintegration.mixin.minecraft.ItemEntityAccessor;
import com.huanghuang.rsintegration.network.binding.AltarBindingRegistry.BoundMachine;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.items.ItemStackHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.isNull;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;

class CraftNodeRuntimeBufferedProgressTest extends BootstrapTest {
    @Test
    void defaultWindowUsesNativeCapacityBeyond256() {
        assertEquals(0, RSIntegrationConfig.CRAFTING_EXECUTION_WINDOW.get());
        ModType type = ModType.register("test_native_capacity",
                new String[0], new String[0], new String[0], () -> new BufferedDelegate() {
                    @Override public int preferredParallelBatchSize(int operations, int workers) {
                        return 4096;
                    }
                });
        BoundMachine machine = new BoundMachine(Level.OVERWORLD.location(), BlockPos.ZERO, type, "test");
        ParallelCraftGroup group = new ParallelCraftGroup(List.of(machine), type,
                new ResourceLocation("test", "native_capacity"), player, 50_000_000);
        startBulkGroup(group, mock(ExtractionLedger.class), false);
        assertEquals(4096, group.getRunningOperations());
        assertEquals(50_000_000, group.getTotalOperations());
        group.onBatchFailed(player, "test cancellation");
    }

    @Test
    void partialRecoveryProofIsSpentOnceAcrossInitialVirtualAndProducerInputs() {
        ModType type = ModType.register("test_mixed_recovery",
                new String[0], new String[0], new String[0], () -> new BufferedDelegate() {
                    @Override public List<ItemStack> failureRecoveredInputs() {
                        return List.of(new ItemStack(Items.IRON_INGOT, 35));
                    }
                });
        BoundMachine machine = new BoundMachine(Level.OVERWORLD.location(), BlockPos.ZERO, type, "test");
        ParallelCraftGroup group = new ParallelCraftGroup(List.of(machine), type,
                new ResourceLocation("test", "mixed_recovery"), player, 128);
        Pool source = new Pool().add(Origin.INITIAL, new ItemStack(Items.IRON_INGOT), 10)
                .add(Origin.VIRTUAL, new ItemStack(Items.IRON_INGOT), 20)
                .add(Origin.PRODUCER, new ItemStack(Items.IRON_INGOT), 98);
        group.setCountedOperationMaterials(new CountedOperationMaterials.Builder(128, 1)
                .add(group.getFlatOperationMaterials().get(0), source).build());
        assertTrue(group.startOperation(OperationStartContext.chainReserved(player,
                mock(ExtractionLedger.class), MaterialPlan.none(), List.of(), InputBufferPlan.none())));
        assertEquals(64, group.getRunningOperations());
        group.onBatchFailed(player, "partial recovery");
        assertEquals(10, group.failureRecoveredInputs().stream().mapToInt(ItemStack::getCount).sum());
        assertEquals(20, group.drainRecoveredVirtualInputs().stream().mapToInt(ItemStack::getCount).sum());
        assertEquals(5, group.failureRecoveredProducerInputs().stream().mapToInt(ItemStack::getCount).sum());
        assertEquals(64, group.drainQueuedProducerMaterialsForRecovery().stream().mapToInt(ItemStack::getCount).sum());
        group.onBatchFailed(player, "repeated cancellation");
        assertTrue(group.drainRecoveredVirtualInputs().isEmpty());
        assertTrue(group.drainQueuedProducerMaterialsForRecovery().isEmpty());
    }

    @Test
    void missingRecoveryAccountingDoesNotEraseAnotherWorkersProofOrRefundUnknownInputs() {
        AtomicInteger created = new AtomicInteger();
        ModType type = ModType.register("test_incomplete_recovery",
                new String[0], new String[0], new String[0], () -> {
                    boolean audited = created.getAndIncrement() == 0;
                    return new BufferedDelegate() {
                        @Override public List<ItemStack> failureRecoveredInputs() {
                            return audited ? List.of(new ItemStack(Items.IRON_INGOT, 7)) : null;
                        }
                    };
                });
        List<BoundMachine> machines = List.of(
                new BoundMachine(Level.OVERWORLD.location(), BlockPos.ZERO, type, "test"),
                new BoundMachine(Level.OVERWORLD.location(), BlockPos.ZERO.east(), type, "test"));
        ParallelCraftGroup group = new ParallelCraftGroup(machines, type,
                new ResourceLocation("test", "incomplete_recovery"), player, 128);
        startBulkGroup(group, mock(ExtractionLedger.class), false);
        assertEquals(128, group.getRunningOperations());
        group.onBatchFailed(player, "partially audited cleanup");
        assertEquals(7, group.failureRecoveredInputs().stream().mapToInt(ItemStack::getCount).sum());
        assertTrue(group.failureRecoveredProducerInputs().isEmpty());
    }

    @Test
    @SuppressWarnings("unchecked")
    void flatReservationUsesTheSameCountedEntryForVirtualAndStoredVariants() throws Exception {
        ParallelCraftGroup group = createGroup(50_000_000);
        AsyncCraftChain chain = new AsyncCraftChain(UUID.randomUUID(), player.getServer(), null, List.of());
        Field inventoryField = AsyncCraftChain.class.getDeclaredField("virtualInventory");
        inventoryField.setAccessible(true);
        List<ItemStack> virtual = (List<ItemStack>) inventoryField.get(chain);
        ItemStack intermediate = new ItemStack(Items.IRON_INGOT, 7_000_000);
        intermediate.getOrCreateTag().putString("source", "intermediate");
        virtual.add(intermediate);
        ExtractionLedger ledger = mock(ExtractionLedger.class);
        when(ledger.reserveExactAcrossNetworkAndInventory(any(ItemStack.class), anyInt(), isNull(), eq(player)))
                .thenAnswer(call -> ((ItemStack) call.getArgument(0)).copyWithCount(call.getArgument(1)));
        Method reserve = AsyncCraftChain.class.getDeclaredMethod("reserveCountedOperationMaterials",
                ParallelCraftGroup.class, ServerPlayer.class,
                ExtractionLedger.class, MaterialBroker.ReservationToken.class);
        reserve.setAccessible(true);
        try (MockedStatic<MaterialSources> sources = mockStatic(MaterialSources.class)) {
            sources.when(() -> MaterialSources.listAllAvailable(player, null))
                    .thenReturn(Map.of(new StackKey(Items.IRON_INGOT, null), 43_000_000L));
            assertNotNull(reserve.invoke(chain, group, player, ledger, null));
        }
        assertTrue(group.usesCountedOperationMaterials());
        assertTrue(virtual.stream().allMatch(ItemStack::isEmpty));
        verify(ledger).reserveExactAcrossNetworkAndInventory(any(ItemStack.class), eq(43_000_000), isNull(), eq(player));
        assertTrue(group.startOperation(OperationStartContext.chainReserved(
                player, ledger, MaterialPlan.none(), List.of(), InputBufferPlan.none())));
        group.stopDispatch();
        List<ItemStack> refunded = group.drainQueuedMaterialsForRecovery();
        assertEquals(7_000_000 - 64, refunded.stream().mapToInt(ItemStack::getCount).sum());
        assertEquals("intermediate", refunded.get(0).getTag().getString("source"));
        group.onBatchFailed(player, "flat cancellation");
    }

    @Test
    void optionalWindowRestrictsOnlyTheNativeBatchAndKeepsTheOrderQuantity() {
        RSIntegrationConfig.CRAFTING_EXECUTION_WINDOW.set(8);
        ParallelCraftGroup group = createGroup(50_000_000);
        startBulkGroup(group, mock(ExtractionLedger.class), false);
        assertEquals(8, group.getRunningOperations());
        assertEquals(50_000_000, group.getTotalOperations());
        group.onBatchFailed(player, "test cancellation");
    }

    @Test
    void cancellingLargeOrdersClassifiesQuantitiesWithoutExpandingEachOperation() {
        ParallelCraftGroup group = createGroup(50_000_000);
        startBulkGroup(group, mock(ExtractionLedger.class), false);
        TerminationCoordinator coordinator = new TerminationCoordinator(UUID.randomUUID(),
                TerminationCoordinator.Cause.CANCELLED, "quantity audit");
        runtime(group).classifyTermination(coordinator);
        coordinator.classify(TerminationCoordinator.OperationState.SETTLED, 3_000_000_000L);
        assertEquals(64, coordinator.report().inFlightOperations());
        assertEquals(49_999_936, coordinator.report().preStartOperations());
        assertEquals(3_000_000_000L, coordinator.report().settledOperations());
        group.onBatchFailed(player, "test cancellation");
    }
    @Test
    void graphReservationEntryUsesOnePhysicalReservationForFiftyMillionOperations() throws Exception {
        int operations = 50_000_000;
        ParallelCraftGroup group = createGroup(operations);
        MaterialBroker broker = new MaterialBroker();
        MaterialKey iron = MaterialKey.of(new ItemStack(Items.IRON_INGOT));
        var source = new MaterialSource.InitialPool(iron);
        broker.publish(source, iron, operations);
        var token = broker.reserve(new NodeId(0), List.of(new MaterialBroker.Request(source, iron, operations)));
        AsyncCraftChain chain = new AsyncCraftChain(UUID.randomUUID(), player.getServer(), null, List.of());
        Field materials = AsyncCraftChain.class.getDeclaredField("graphMaterials");
        materials.setAccessible(true);
        materials.set(chain, broker);
        ExtractionLedger ledger = mock(ExtractionLedger.class);
        AtomicInteger reservationCalls = new AtomicInteger();
        when(ledger.reserveExactAcrossNetworkAndInventory(any(ItemStack.class), anyInt(), isNull(), eq(player)))
                .thenAnswer(call -> {
                    assertEquals(1, reservationCalls.incrementAndGet(), "同质批量不能逐次预留");
                    return ((ItemStack) call.getArgument(0)).copyWithCount(call.getArgument(1));
                });
        Method reserve = AsyncCraftChain.class.getDeclaredMethod("reserveCountedOperationMaterials",
                ParallelCraftGroup.class, ServerPlayer.class,
                ExtractionLedger.class, MaterialBroker.ReservationToken.class);
        reserve.setAccessible(true);
        assertNotNull(reserve.invoke(chain, group, player, ledger, token));
        assertTrue(group.usesCountedOperationMaterials());
        verify(ledger).reserveExactAcrossNetworkAndInventory(any(ItemStack.class), eq(operations), isNull(), eq(player));
        assertEquals(operations, broker.heldBy(new NodeId(0)));
        group.releasePreparationResources();
    }

    @Test
    void compactPlanExecutesSeveralWorkerBatchesAndSettlesOnlyTheirQuantities() {
        int operations = 129;
        ParallelCraftGroup group = createGroup(operations);
        ExtractionLedger ledger = mock(ExtractionLedger.class);
        startBulkGroup(group, ledger, false);
        CraftNodeRuntime runtime = runtime(group);
        for (int i = 0; i < operations; i++) {
            produceOutput();
            tick.incrementAndGet();
            assertEquals(i + 1 == operations ? ConcurrentNodeExecutor.Observation.SUCCEEDED
                    : ConcurrentNodeExecutor.Observation.WORKING, runtime.observe());
        }
        ArgumentCaptor<List<ItemStack>> settled = stackLists();
        verify(ledger, times(3)).releaseCommittedEntries(settled.capture());
        assertEquals(List.of(64, 64, 1), settled.getAllValues().stream()
                .map(stacks -> stacks.stream().mapToInt(ItemStack::getCount).sum()).toList());
        assertEquals(operations, group.getCompletedOperations());
    }

    @Test
    void fiftyMillionCompactOperationsStartWithoutExpandingAndRefundOnlyQueuedInputs() {
        int operations = 50_000_000;
        ParallelCraftGroup group = createGroup(operations);
        ExtractionLedger ledger = mock(ExtractionLedger.class);
        startBulkGroup(group, ledger, false);
        assertEquals(64, group.getRunningOperations());
        group.stopDispatch();
        group.refundQueuedBulkMaterials(player);
        group.refundQueuedBulkMaterials(player);
        ArgumentCaptor<List<ItemStack>> refunded = stackLists();
        verify(ledger).refundCommittedMaterials(refunded.capture(), isNull(), eq(player));
        assertEquals(operations - 64, refunded.getValue().stream().mapToInt(ItemStack::getCount).sum());
        group.onBatchFailed(player, "test cancellation");
    }

    @Test
    void compactProducerRecoveryExcludesRunningBatchAndCannotRepeat() {
        ParallelCraftGroup group = createGroup(129);
        startBulkGroup(group, mock(ExtractionLedger.class), true);
        produceOutput();
        produceOutput();
        group.stopDispatch();
        var queued = group.drainQueuedProducerMaterialsForRecovery();
        assertEquals(65, queued.stream().mapToInt(ItemStack::getCount).sum());
        assertTrue(group.drainQueuedProducerMaterialsForRecovery().isEmpty());
        group.onBatchFailed(player, "test cancellation");
        assertEquals(16, group.drainSettledResults().stream().mapToInt(ItemStack::getCount).sum());
    }

    @Test
    void rejectedCompactBatchRecoversOnlyUncompletedInputs() {
        ModType type = ModType.register("test_rejected_compact",
                new String[0], new String[0], new String[0], () -> new BufferedDelegate() {
                    @Override public boolean startOperation(OperationStartContext context) {
                        produceOutput();
                        return false;
                    }
                });
        BoundMachine machine = new BoundMachine(Level.OVERWORLD.location(), BlockPos.ZERO, type, "test");
        ParallelCraftGroup group = new ParallelCraftGroup(List.of(machine), type,
                new ResourceLocation("test", "rejected_compact"), player, 129);
        ExtractionLedger ledger = mock(ExtractionLedger.class);
        startBulkGroup(group, ledger, false);
        assertTrue(group.isDraining());
        group.refundQueuedBulkMaterials(player);
        ArgumentCaptor<List<ItemStack>> refunded = stackLists();
        verify(ledger).refundCommittedMaterials(refunded.capture(), isNull(), eq(player));
        assertEquals(65, refunded.getValue().stream().mapToInt(ItemStack::getCount).sum(),
                "接口调用后未确认恢复的 63 份投入不能直接退款");
        ArgumentCaptor<List<ItemStack>> settled = stackLists();
        verify(ledger).releaseCommittedEntries(settled.capture());
        assertEquals(1, settled.getValue().get(0).getCount());
        assertEquals(8, group.drainSettledResults().stream().mapToInt(ItemStack::getCount).sum());
        group.onBatchFailed(player, "test rejection");
        assertEquals(OperationExecutionKernel.TerminalClass.IN_FLIGHT, group.failureTerminalClass());
        group.onBatchFailed(player, "repeated rejection");
        assertEquals(OperationExecutionKernel.TerminalClass.IN_FLIGHT, group.failureTerminalClass());
    }

    private void startBulkGroup(ParallelCraftGroup group, ExtractionLedger ledger, boolean producer) {
        List<ItemStack> pool = List.of(new ItemStack(Items.IRON_INGOT, group.getTotalOperations()));
        Pool source = new Pool().add(producer ? Origin.PRODUCER : Origin.INITIAL, pool);
        var builder = new CountedOperationMaterials.Builder(group.getTotalOperations(), group.getChildCount());
        for (IngredientSpec spec : group.getFlatOperationMaterials()) builder.add(spec, source);
        group.setCountedOperationMaterials(builder.build());
        assertTrue(group.startOperation(OperationStartContext.chainReserved(
                player, ledger, MaterialPlan.none(), List.of(), InputBufferPlan.none())));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ArgumentCaptor<List<ItemStack>> stackLists() {
        return ArgumentCaptor.forClass((Class) List.class);
    }

    private final AtomicLong tick = new AtomicLong();
    private ServerLevel level;
    private ServerPlayer player;
    private ItemEntity outputEntity;

    @BeforeAll
    static void initializeModRuntime() {
        resetConfig();
        FMLJavaModLoadingContext context = mock(FMLJavaModLoadingContext.class);
        when(context.getModEventBus()).thenReturn(mock(IEventBus.class));
        try (MockedStatic<FMLJavaModLoadingContext> loading = mockStatic(FMLJavaModLoadingContext.class)) {
            loading.when(FMLJavaModLoadingContext::get).thenReturn(context);
            assertNotNull(RSIntegrationMod.LOGGER);
        }
    }

    @BeforeEach
    void createContextWithShortIdleTimeout() {
        resetConfig();
        RSIntegrationConfig.MULTIBLOCK_CRAFT_TIMEOUT_SECONDS.set(10);
        MinecraftServer server = mock(MinecraftServer.class);
        level = mock(ServerLevel.class);
        player = mock(ServerPlayer.class);
        when(player.getServer()).thenReturn(server);
        when(player.serverLevel()).thenReturn(level);
        when(server.getTickCount()).thenAnswer(call -> Math.toIntExact(tick.get()));
        when(level.getGameTime()).thenAnswer(call -> tick.get());
        when(level.dimension()).thenReturn(Level.OVERWORLD);
        outputEntity = mock(ItemEntity.class, withSettings().extraInterfaces(ItemEntityAccessor.class));
        when(outputEntity.position()).thenReturn(Vec3.ZERO);
    }

    @AfterEach
    void restoreConfig() {
        CraftOutputInterceptor.clearAll();
        resetConfig();
    }

    private static void resetConfig() {
        CommentedConfig config = CommentedConfig.inMemory();
        RSIntegrationConfig.SERVER_SPEC.correct(config);
        RSIntegrationConfig.SERVER_SPEC.setConfig(config);
    }

    @ParameterizedTest
    @ValueSource(ints = {129, 1024})
    void incrementalNativeOutputsKeepLongBufferedOrderAlive(int operations) {
        ParallelCraftGroup group = startGroup(operations);
        CraftNodeRuntime runtime = runtime(group);

        for (int operation = 0; operation < operations; operation++) {
            produceOutput();
            tick.incrementAndGet();
            ConcurrentNodeExecutor.Observation result = runtime.observe();
            assertTrue(runtime.consumeProgressSignal());
            if (operation + 1 == operations) {
                assertEquals(ConcurrentNodeExecutor.Observation.SUCCEEDED, result);
            } else {
                assertEquals(ConcurrentNodeExecutor.Observation.WORKING, result);
                assertEquals(0, runtime.waitTicks());
                tick.incrementAndGet();
                assertEquals(ConcurrentNodeExecutor.Observation.WORKING, runtime.observe());
                assertFalse(runtime.consumeProgressSignal());
            }
            if (operation < 63) {
                assertEquals(0, group.getCompletedOperations(),
                        "子批次尚未完成时，也必须观察到每一轮实际产物");
            }
        }
        assertTrue(tick.get() > timeoutTicks());
        assertEquals(operations, runtime.completedOperations());
    }

    @Test
    void parallelWorkerKeepsCatalystUntilItsWholeBufferedOrderCompletes() {
        int operations = 129;
        ItemStackHandler inventory = new ItemStackHandler(1);
        AtomicInteger collections = new AtomicInteger();
        BufferedDelegate delegate = new BufferedDelegate() {
            @Override
            public List<IngredientSpec> getRequiredMaterials() {
                return List.of(
                        new IngredientSpec(Ingredient.of(Items.STICK), 1, DemandRole.CATALYST),
                        new IngredientSpec(Ingredient.of(Items.IRON_INGOT), 1));
            }

            @Override
            public boolean supportsReusableBatchAggregation() {
                return true;
            }

            @Override
            public boolean startOperation(OperationStartContext context) {
                if (inventory.getStackInSlot(0).isEmpty()) {
                    inventory.setStackInSlot(0, context.legacyMaterials().get(0).copy());
                }
                return true;
            }

            @Override
            public List<ItemStack> collectReusableMaterials(ServerPlayer player) {
                collections.incrementAndGet();
                ItemStack catalyst = inventory.extractItem(0, 1, false);
                return catalyst.isEmpty() ? List.of() : List.of(catalyst);
            }
        };
        ModType type = ModType.register("test_buffered_catalyst_return",
                new String[0], new String[0], new String[0], () -> delegate);
        BoundMachine machine = new BoundMachine(Level.OVERWORLD.location(), BlockPos.ZERO, type, "test");
        ParallelCraftGroup group = new ParallelCraftGroup(
                List.of(machine), type, new ResourceLocation("test", "catalyst"), player, operations);
        group.setReservationTokens(Collections.nCopies(
                operations, mock(ExtractionLedger.ReservationToken.class)));
        group.setVirtualDebits(Collections.nCopies(operations, List.of()));
        List<ItemStack> materials = new ArrayList<>();
        for (int i = 0; i < operations; i++) {
            materials.add(new ItemStack(Items.STICK));
            materials.add(new ItemStack(Items.IRON_INGOT));
        }
        assertTrue(group.startOperation(OperationStartContext.repeatedChainReserved(
                player, mock(ExtractionLedger.class), group.operationMaterialPlan(),
                operations, 2, materials)));
        NodeId node = new NodeId(0);
        CraftNodeRuntime runtime = runtime(group);
        runtime.attachOutputs(new NodeOutputAccumulator(List.of(
                OutputDeclaration.item(new OutputPortId(node, 0),
                        MaterialKey.of(new ItemStack(Items.DIAMOND)), operations * 8L, OutputKind.PRIMARY),
                OutputDeclaration.item(new OutputPortId(node, 1),
                        MaterialKey.of(new ItemStack(Items.STICK)), 1, OutputKind.CATALYST_RETURN,
                        1, 1, OutputProbability.guaranteed(), false, new InputPortId(node, 0)))));

        for (int i = 1; i <= operations; i++) {
            produceOutput();
            tick.incrementAndGet();
            assertEquals(i == operations ? ConcurrentNodeExecutor.Observation.SUCCEEDED
                    : ConcurrentNodeExecutor.Observation.WORKING, runtime.observe());
            runtime.drainIncrementalOutputs();
            if (i < operations) {
                assertEquals(0, collections.get());
                assertEquals(1, inventory.getStackInSlot(0).getCount());
            }
        }

        assertEquals(1, collections.get());
        assertTrue(inventory.getStackInSlot(0).isEmpty());
        assertTrue(runtime.outputsComplete());
        assertTrue(runtime.drainIncrementalOutputs().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void freshWorkerDelegatesTransferTheirActualCatalystOrStopWithoutLosingIt(boolean rejectHandoff) {
        int operations = 129;
        AtomicInteger created = new AtomicInteger();
        AtomicInteger starts = new AtomicInteger();
        AtomicInteger handoffs = new AtomicInteger();
        List<ItemStack> returned = new ArrayList<>();
        class OwnedCatalystDelegate extends BufferedDelegate {
            final boolean first = created.getAndIncrement() == 0;
            ItemStack held = ItemStack.EMPTY;
            boolean finished;

            @Override
            public List<IngredientSpec> getRequiredMaterials() {
                return List.of(
                        new IngredientSpec(Ingredient.of(Items.STICK), 1, DemandRole.CATALYST),
                        new IngredientSpec(Ingredient.of(Items.IRON_INGOT), 1));
            }

            @Override
            public boolean supportsReusableBatchAggregation() {
                return true;
            }

            @Override
            public boolean startOperation(OperationStartContext context) {
                assertFalse(finished, "已经结束的操作实例不能再次启动");
                if (first) held = context.legacyMaterials().get(0).copy();
                if (held.isEmpty()) return false;
                starts.incrementAndGet();
                return true;
            }

            @Override
            public void onBatchFinished(ServerPlayer player) {
                assertFalse(finished);
                finished = true;
            }

            @Override
            public boolean transferReusableMaterialsTo(IBatchDelegate successor) {
                assertTrue(finished);
                handoffs.incrementAndGet();
                if (rejectHandoff) return false;
                OwnedCatalystDelegate next = (OwnedCatalystDelegate) successor;
                assertTrue(next.held.isEmpty());
                next.held = held;
                held = ItemStack.EMPTY;
                return true;
            }

            @Override
            public List<ItemStack> collectReusableMaterials(ServerPlayer player) {
                if (held.isEmpty()) return List.of();
                List<ItemStack> actual = List.of(held);
                returned.add(held);
                held = ItemStack.EMPTY;
                return actual;
            }

            @Override
            public void releaseReusableMaterials(ServerPlayer player) {
                collectReusableMaterials(player);
            }
        }
        ModType type = ModType.register("test_fresh_catalyst_" + rejectHandoff,
                new String[0], new String[0], new String[0], OwnedCatalystDelegate::new);
        BoundMachine machine = new BoundMachine(Level.OVERWORLD.location(), BlockPos.ZERO, type, "test");
        ParallelCraftGroup group = new ParallelCraftGroup(
                List.of(machine), type, new ResourceLocation("test", "fresh_catalyst"), player, operations);
        group.setReservationTokens(Collections.nCopies(
                operations, mock(ExtractionLedger.ReservationToken.class)));
        group.setVirtualDebits(Collections.nCopies(operations, List.of()));
        List<ItemStack> materials = new ArrayList<>();
        for (int i = 0; i < operations; i++) {
            ItemStack catalyst = new ItemStack(Items.STICK);
            catalyst.getOrCreateTag().putString("owner", "only-real-catalyst");
            materials.add(catalyst);
            materials.add(new ItemStack(Items.IRON_INGOT));
        }
        assertTrue(group.startOperation(OperationStartContext.repeatedChainReserved(
                player, mock(ExtractionLedger.class), group.operationMaterialPlan(),
                operations, 2, materials)));
        CraftNodeRuntime runtime = runtime(group);
        int completed = rejectHandoff ? 64 : operations;
        for (int i = 1; i <= completed; i++) {
            produceOutput();
            tick.incrementAndGet();
            assertEquals(i == completed
                            ? rejectHandoff ? ConcurrentNodeExecutor.Observation.FAILED
                            : ConcurrentNodeExecutor.Observation.SUCCEEDED
                            : ConcurrentNodeExecutor.Observation.WORKING,
                    runtime.observe());
            if (i < completed) assertTrue(returned.isEmpty());
        }
        if (rejectHandoff) runtime.cleanupFailure();

        assertEquals(rejectHandoff ? 2 : 3, created.get());
        assertEquals(rejectHandoff ? 1 : 3, starts.get());
        assertEquals(rejectHandoff ? 1 : 2, handoffs.get());
        assertEquals(completed, group.getCompletedOperations());
        assertEquals(1, returned.size());
        assertEquals("only-real-catalyst", returned.get(0).getTag().getString("owner"));
        assertEquals(1, returned.get(0).getCount());
        assertEquals(0, group.getRunningOperations());
    }

    @Test
    void machineWorkWithoutOutputsKeepsNodeAndChainAliveThenStallsNormally() {
        AtomicLong work = new AtomicLong();
        IBatchDelegate delegate = new BufferedDelegate() {
            @Override
            public CraftObservation observeCraft(ServerLevel current) {
                return new CraftObservation(CraftPhase.WORKING).withCompletedWork(work.get());
            }
        };
        CraftNodeRuntime runtime = new CraftNodeRuntime(new NodeId(0), "test:long_work", delegate, null, null);
        runtime.setChainContext(new ArrayList<>(), player);
        ProgressWatchdog chainWatchdog = new ProgressWatchdog(timeoutTicks());
        for (int elapsed = 0; elapsed < timeoutTicks() * 3; elapsed++) {
            work.incrementAndGet();
            assertEquals(ConcurrentNodeExecutor.Observation.WORKING, runtime.observe());
            assertTrue(runtime.consumeProgressSignal());
            chainWatchdog.markProgress();
            assertFalse(chainWatchdog.tick());
        }
        long maximum = work.get();
        // 进度倒退再回到旧值不属于新进度，不能靠反复重启同一工序永远续命。
        for (int idle = 0; idle < timeoutTicks(); idle++) {
            work.set(idle % 2 == 0 ? 0L : maximum);
            assertEquals(ConcurrentNodeExecutor.Observation.WORKING, runtime.observe());
            assertFalse(runtime.consumeProgressSignal());
        }
        assertEquals(ConcurrentNodeExecutor.Observation.FAILED, runtime.observe());
        assertEquals(CraftFailureCode.TIMEOUT, runtime.failureCode());
    }

    @Test
    void parallelWorkerWorkIsForwardedBeforeAnyOutputAndResetForTheNextOperation() {
        AtomicLong work = new AtomicLong();
        ModType type = ModType.register("test_machine_work_progress",
                new String[0], new String[0], new String[0], () -> new BufferedDelegate() {
                    @Override
                    public int preferredParallelBatchSize(int operations, int workers) { return 1; }

                    @Override
                    public CraftObservation observeCraft(ServerLevel current) {
                        return new CraftObservation(CraftPhase.WORKING).withCompletedWork(work.get());
                    }
                });
        BoundMachine machine = new BoundMachine(Level.OVERWORLD.location(), BlockPos.ZERO, type, "test");
        ParallelCraftGroup group = new ParallelCraftGroup(List.of(machine), type,
                new ResourceLocation("test", "long_work"), player, 2);
        startGroup(group);
        CraftNodeRuntime runtime = runtime(group);
        for (int elapsed = 0; elapsed < timeoutTicks() * 3; elapsed++) {
            work.incrementAndGet();
            tick.incrementAndGet();
            assertEquals(ConcurrentNodeExecutor.Observation.WORKING, runtime.observe());
            assertTrue(runtime.consumeProgressSignal());
            assertEquals(0, group.getCompletedOperations());
        }
        produceOutput();
        tick.incrementAndGet();
        assertEquals(ConcurrentNodeExecutor.Observation.WORKING, runtime.observe());
        assertEquals(1, group.getCompletedOperations());
        assertTrue(runtime.consumeProgressSignal());
        work.set(1L);
        tick.incrementAndGet();
        assertEquals(ConcurrentNodeExecutor.Observation.WORKING, runtime.observe());
        assertTrue(runtime.consumeProgressSignal());
        for (int idle = 0; idle < timeoutTicks(); idle++) {
            tick.incrementAndGet();
            assertEquals(ConcurrentNodeExecutor.Observation.WORKING, runtime.observe());
            assertFalse(runtime.consumeProgressSignal());
        }
        assertEquals(ConcurrentNodeExecutor.Observation.FAILED, runtime.observe());
        assertEquals(CraftFailureCode.TIMEOUT, runtime.failureCode());
        runtime.cleanupFailure();
    }

    @Test
    void repeatedSnapshotWithoutNewOutputStillTimesOut() {
        ParallelCraftGroup group = startGroup(129);
        CraftNodeRuntime runtime = runtime(group);
        produceOutput();
        assertEquals(ConcurrentNodeExecutor.Observation.WORKING, runtime.observe());
        assertTrue(runtime.consumeProgressSignal());

        for (int idle = 0; idle < timeoutTicks(); idle++) {
            tick.incrementAndGet();
            assertEquals(ConcurrentNodeExecutor.Observation.WORKING, runtime.observe());
            assertFalse(runtime.consumeProgressSignal());
        }
        tick.incrementAndGet();
        assertEquals(ConcurrentNodeExecutor.Observation.FAILED, runtime.observe());
        assertEquals(CraftFailureCode.TIMEOUT, runtime.failureCode());
        runtime.cleanupFailure();
    }

    @Test
    void leasedMachineQueueOutlivesStartDeadlineAndStillTimesOutAfterARealStall() {
        ParallelCraftGroup group = createGroup(129);
        MachineLeaseRegistry leases = new MachineLeaseRegistry();
        var machine = new MachineLeaseRegistry.MachineKey(
                Level.OVERWORLD.location(), BlockPos.ZERO, "test_buffered_progress");
        var lease = leases.tryAcquire(machine, new OperationId(UUID.randomUUID(), new NodeId(1), 0));
        group.setMachineLeases(leases);
        group.setOperationKernel(new OperationExecutionKernel(new OperationResourceCoordinator(
                        leases, new CaptureLeaseRegistry(), new OperationBudget(1, 10_000))),
                UUID.randomUUID(), new NodeId(0), new OperationBudget(1, 10_000));
        startGroup(group);
        CraftNodeRuntime runtime = runtime(group);

        for (int elapsed = 0; elapsed <= 1200; elapsed++) {
            tick.incrementAndGet();
            assertEquals(ConcurrentNodeExecutor.Observation.WORKING, runtime.observe());
            assertTrue(group.isWaitingForLeasedMachine());
            assertFalse(runtime.consumeProgressSignal());
            assertEquals(0, group.getRunningOperations());
        }
        assertTrue(leases.release(lease));
        tick.addAndGet(20);
        assertEquals(ConcurrentNodeExecutor.Observation.WORKING, runtime.observe());
        assertFalse(group.isWaitingForLeasedMachine());
        assertTrue(group.getRunningOperations() > 0);
        produceOutput();
        tick.incrementAndGet();
        assertEquals(ConcurrentNodeExecutor.Observation.WORKING, runtime.observe());
        assertTrue(runtime.consumeProgressSignal());
        for (int idle = 0; idle < timeoutTicks(); idle++) {
            tick.incrementAndGet();
            assertEquals(ConcurrentNodeExecutor.Observation.WORKING, runtime.observe());
        }
        tick.incrementAndGet();
        assertEquals(ConcurrentNodeExecutor.Observation.FAILED, runtime.observe());
        assertEquals(CraftFailureCode.TIMEOUT, runtime.failureCode());
        runtime.cleanupFailure();
        assertEquals(0, leases.size());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 129})
    void virtualGraphWorkerStartsAndCompletesWithoutPhysicalMachineLeases(int operations) {
        AtomicInteger starts = new AtomicInteger();
        MachineLeaseRegistry machines = new MachineLeaseRegistry();
        CaptureLeaseRegistry captures = new CaptureLeaseRegistry();
        int expectedStarts = (operations + 63) / 64;
        OperationBudget globalBudget = new OperationBudget(1, expectedStarts);
        OperationBudget craftBudget = new OperationBudget(1, expectedStarts);
        ModType type = ModType.registerVirtual("test_virtual_operation_resources",
                new String[0], () -> new BufferedDelegate() {
                    @Override
                    public BatchConcurrencyCapabilities concurrencyCapabilities() {
                        return BatchConcurrencyCapabilities.virtualTransaction();
                    }

                    @Override
                    public boolean startOperation(OperationStartContext context) {
                        starts.incrementAndGet();
                        assertEquals(1, craftBudget.active());
                        assertEquals(1, globalBudget.active());
                        assertEquals(0, machines.size());
                        return true;
                    }

                    @Override
                    public boolean isCraftComplete(ServerLevel level) { return true; }

                    @Override
                    public ItemStack getExpectedOutput() { return ItemStack.EMPTY; }

                    @Override
                    public AABB getOutputCaptureRegion() { return null; }

                    @Override
                    public List<ItemStack> collectAllResults(ServerPlayer player) {
                        return List.of(new ItemStack(Items.DIAMOND, getExpectedProduction().count()));
                    }
                });
        BoundMachine machine = new BoundMachine(Level.OVERWORLD.location(), BlockPos.ZERO, type, "test");
        ParallelCraftGroup group = new ParallelCraftGroup(List.of(machine), type,
                new ResourceLocation("malum", "void_favor/malignant_lead"), player, operations);
        group.setOperationKernel(new OperationExecutionKernel(new OperationResourceCoordinator(
                machines, captures, globalBudget)), UUID.randomUUID(), new NodeId(5), craftBudget);
        startGroup(group);

        IBatchDelegate.CraftObservation observation = null;
        for (int i = 0; i < expectedStarts + 1; i++) {
            tick.incrementAndGet();
            observation = group.observeCraft(level);
            if (observation.phase() == IBatchDelegate.CraftPhase.DONE) break;
        }
        assertNotNull(observation);
        assertEquals(IBatchDelegate.CraftPhase.DONE, observation.phase());
        assertEquals(operations, group.getCompletedOperations());
        assertEquals(expectedStarts, starts.get());
        assertEquals(expectedStarts, craftBudget.starts());
        assertEquals(expectedStarts, globalBudget.starts());
        assertEquals(0, craftBudget.active());
        assertEquals(0, globalBudget.active());
        assertEquals(0, machines.size());
        assertEquals(0, captures.size());
        assertEquals(operations * 8,
                group.collectAllResults(player).stream().mapToInt(ItemStack::getCount).sum());
    }

    @Test
    void queueTimeDoesNotConsumeLaterMaterialRetryWindow() throws Exception {
        AsyncCraftChain chain = new AsyncCraftChain(
                UUID.randomUUID(), player.getServer(), null, List.of());
        String machineBusy = "所有已绑定机器都在忙碌、未加载或不可用";
        assertEquals(ConcurrentNodeExecutor.StartStatus.RETRY, retry(chain, machineBusy));
        tick.set(1000);
        assertEquals(ConcurrentNodeExecutor.StartStatus.RETRY, retry(chain, machineBusy));
        assertEquals(ConcurrentNodeExecutor.StartStatus.RETRY, retry(chain, "missing material"));
        tick.set(1600);
        assertEquals(ConcurrentNodeExecutor.StartStatus.RETRY, retry(chain, "missing material"));
        tick.set(1601);
        assertEquals(ConcurrentNodeExecutor.StartStatus.FAILED, retry(chain, "missing material"));
    }

    private static ConcurrentNodeExecutor.StartStatus retry(
            AsyncCraftChain chain, String detail) throws Exception {
        Method method = AsyncCraftChain.class.getDeclaredMethod(
                "graphRetry", NodeId.class, String.class, List.class);
        method.setAccessible(true);
        return ((ConcurrentNodeExecutor.StartResult) method.invoke(
                chain, new NodeId(0), detail, List.of())).status();
    }

    private static int timeoutTicks() {
        return RSIntegrationConfig.MULTIBLOCK_CRAFT_TIMEOUT_SECONDS.get() * 20;
    }

    private CraftNodeRuntime runtime(ParallelCraftGroup group) {
        CraftNodeRuntime runtime = new CraftNodeRuntime(
                new NodeId(0), "malum:spirit_crucible/large_order", group, null, null);
        runtime.setChainContext(new ArrayList<>(), player);
        return runtime;
    }

    private void produceOutput() {
        when(outputEntity.getItem()).thenReturn(new ItemStack(Items.DIAMOND, 8));
        EntityJoinLevelEvent event = new EntityJoinLevelEvent(outputEntity, level);
        CraftOutputInterceptor.onEntityJoin(event);
        assertTrue(event.isCanceled());
    }

    private ParallelCraftGroup startGroup(int operations) {
        ParallelCraftGroup group = createGroup(operations);
        startGroup(group);
        return group;
    }

    private ParallelCraftGroup createGroup(int operations) {
        ModType type = ModType.register("test_buffered_progress",
                new String[0], new String[0], new String[0], BufferedDelegate::new);
        BoundMachine machine = new BoundMachine(new ResourceLocation("minecraft", "overworld"),
                BlockPos.ZERO, type, "test");
        return new ParallelCraftGroup(
                List.of(machine), type, new ResourceLocation("test", "buffered"), player, operations);
    }

    private void startGroup(ParallelCraftGroup group) {
        int operations = group.getTotalOperations();
        group.setReservationTokens(Collections.nCopies(
                operations, mock(ExtractionLedger.ReservationToken.class)));
        group.setVirtualDebits(Collections.nCopies(operations, List.of()));
        ExtractionLedger ledger = mock(ExtractionLedger.class);
        List<ItemStack> materials = Collections.nCopies(operations, new ItemStack(Items.IRON_INGOT));
        assertTrue(group.startOperation(OperationStartContext.repeatedChainReserved(
                player, ledger, group.operationMaterialPlan(), operations, 1, materials)));
    }

    private static class BufferedDelegate implements IBatchDelegate {
        private int batchSize = 1;

        @Override
        public boolean validateAndInit(ServerPlayer player, ResourceLocation recipeId,
                                       ResourceLocation dimension, BlockPos pos) {
            return true;
        }

        @Override
        public List<IngredientSpec> getRequiredMaterials() {
            return List.of(new IngredientSpec(Ingredient.of(Items.IRON_INGOT), 1));
        }

        @Override
        public int preferredParallelBatchSize(int operations, int workers) {
            return 64;
        }

        @Override
        public void prepareGraphBatch(int executions) {
            batchSize = executions;
        }

        @Override
        public boolean startOperation(OperationStartContext context) {
            return true;
        }

        @Override
        public boolean isCraftComplete(ServerLevel level) {
            return false;
        }

        @Override
        public ItemStack getExpectedOutput() {
            return new ItemStack(Items.DIAMOND, 8);
        }

        @Override
        public ExpectedProduction getExpectedProduction() {
            return new ExpectedProduction(new ItemStack(Items.DIAMOND), 8 * batchSize);
        }

        @Override
        public AABB getOutputCaptureRegion() {
            return new AABB(BlockPos.ZERO).inflate(3);
        }

        @Override
        public ItemStack collectResult(ServerPlayer player) {
            return ItemStack.EMPTY;
        }

        @Override
        public void onBatchFailed(ServerPlayer player, String reason) {}

        @Override
        public void onBatchFinished(ServerPlayer player) {}

        @Override
        public BlockPos getMachinePos() {
            return BlockPos.ZERO;
        }
    }
}
