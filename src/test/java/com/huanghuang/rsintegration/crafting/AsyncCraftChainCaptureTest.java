package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.api.execution.CancelReason;
import com.huanghuang.rsintegration.api.execution.RecursiveCraftSucceededEvent;

import com.huanghuang.rsintegration.crafting.batch.OperationLifecycle;
import com.huanghuang.rsintegration.crafting.graph.CaptureLeaseRegistry;
import com.huanghuang.rsintegration.crafting.graph.ConcurrentNodeExecutor;
import com.huanghuang.rsintegration.crafting.graph.DagScheduler;
import com.huanghuang.rsintegration.crafting.graph.NodeId;
import com.huanghuang.rsintegration.crafting.graph.NodeOutputAccumulator;
import com.huanghuang.rsintegration.crafting.batch.MachineOperation;
import com.huanghuang.rsintegration.crafting.batch.OperationOutputSource;
import com.electronwill.nightconfig.core.CommentedConfig;
import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.crafting.batch.IBatchDelegate;
import com.huanghuang.rsintegration.crafting.endpoint.CraftStorageEndpoint;
import com.huanghuang.rsintegration.crafting.loadbalancer.ParallelCraftGroup;
import com.huanghuang.rsintegration.storage.StorageOperationMode;
import com.huanghuang.rsintegration.storage.StorageOperationResult;
import com.huanghuang.rsintegration.storage.rs.RefinedStorageOutputReception;
import com.huanghuang.rsintegration.util.PlayerUtils;
import com.refinedmods.refinedstorage.api.network.INetwork;
import com.refinedmods.refinedstorage.api.util.Action;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.MockedStatic;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AsyncCraftChainCaptureTest extends BootstrapTest {
    @ParameterizedTest
    @EnumSource(OutputDestination.class)
    @SuppressWarnings("unchecked")
    void networkCaptureWaitsForFinalDestinationAndNeverReclaimsWriteback(OutputDestination destination) throws Exception {
        Fixture fixture = fixture();
        INetwork network = mock(INetwork.class);
        ItemStack diamond = new ItemStack(Items.DIAMOND);
        var key = TaskOutputReception.resource(diamond);
        AtomicInteger stored = new AtomicInteger();
        AtomicInteger deliveredToPlayer = new AtomicInteger();
        when(network.insertItem(any(), anyInt(), any())).thenAnswer(call ->
                RefinedStorageOutputReception.insertItem(network, call.getArgument(0), call.getArgument(1),
                        call.getArgument(2), (stack, amount) -> {
                            if (call.getArgument(2) == Action.PERFORM) stored.addAndGet(amount);
                            return ItemStack.EMPTY;
                        }));
        CraftStorageEndpoint endpoint = (CraftStorageEndpoint) field(fixture.chain, "storageEndpoint");
        when(endpoint.insert(eq(fixture.player), any(), eq(false))).thenAnswer(call -> {
            ItemStack stack = call.getArgument(1);
            return StorageOperationResult.inserted(StorageOperationMode.PERFORM, stack,
                    network.insertItem(stack, stack.getCount(), Action.PERFORM));
        });
        try (var reception = TaskOutputReception.open(); var otherTask = TaskOutputReception.open();
             var players = mockStatic(PlayerUtils.class)) {
            players.when(() -> PlayerUtils.insertIntoPlayerInventory(eq(fixture.player), any()))
                    .thenAnswer(call -> {
                        deliveredToPlayer.addAndGet(call.getArgument(1, ItemStack.class).getCount());
                        return ItemStack.EMPTY;
                    });
            reception.bindNetwork(network, () -> true);
            reception.prepare(List.of(new TaskOutputReception.Demand("out", key, 3, 3))).activate();
            assertTrue(network.insertItem(diamond, 5, Action.PERFORM).isEmpty());
            assertEquals(2, stored.get(), "仅超额两件进入磁盘，任务截留三件");
            assertEquals(0, deliveredToPlayer.get());
            reception.close();
            List<ItemStack> inventory = (List<ItemStack>) field(fixture.chain, "virtualInventory");
            inventory.addAll(reception.drainStacks());
            otherTask.bindNetwork(network, () -> true);
            otherTask.prepare(List.of(new TaskOutputReception.Demand("out", key, 3, 3))).activate();
            fixture.chain.setOutputDestination(destination);
            set(fixture.chain, "targetOutput", diamond);
            set(fixture.chain, "finalSettlementPrepared", true);
            Method finish = AsyncCraftChain.class.getDeclaredMethod("finish", ServerPlayer.class);
            finish.setAccessible(true);
            var history = (CraftProductionHistory) field(fixture.chain, "productionHistory");
            history.record(diamond, 3);
            var events = new ArrayList<RecursiveCraftSucceededEvent>();
            Consumer<RecursiveCraftSucceededEvent> listener = event -> {
                assertEquals(5, deliveredToPlayer.get() + stored.get(), "成功事件必须在结算之后发出");
                events.add(event);
            };
            // 单元测试未经过 ModLauncher 的事件类转换，先用真实实例初始化监听列表。
            new RecursiveCraftSucceededEvent(fixture.player, fixture.chain.getCraftId(), Map.of()).getListenerList();
            MinecraftForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, RecursiveCraftSucceededEvent.class, listener);
            MinecraftForge.EVENT_BUS.start();
            try {
                assertEquals(true, finish.invoke(fixture.chain, fixture.player));
                assertEquals(destination == OutputDestination.PLAYER_INVENTORY ? 3 : 0, deliveredToPlayer.get());
                assertEquals(destination == OutputDestination.RS_NETWORK ? 5 : 2, stored.get());
                assertEquals(0, otherTask.received(key), "最终写回不能被另一任务再次认领");
                assertTrue(reception.drain().isEmpty());
                assertTrue(fixture.chain.tick());
                assertEquals(5, deliveredToPlayer.get() + stored.get());
                assertEquals(1, events.size());
                assertEquals(fixture.chain.getCraftId(), events.get(0).taskId());
                assertEquals(Map.of(key, 3L), events.get(0).producedItems());
            } finally {
                MinecraftForge.EVENT_BUS.unregister(listener);
                MinecraftForge.EVENT_BUS.shutdown();
            }
        }
    }

    @BeforeAll
    static void initializeRuntime() throws Exception {
        CommentedConfig config = CommentedConfig.inMemory();
        RSIntegrationConfig.SERVER_SPEC.correct(config);
        RSIntegrationConfig.SERVER_SPEC.setConfig(config);
        FMLJavaModLoadingContext context = mock(FMLJavaModLoadingContext.class);
        when(context.getModEventBus()).thenReturn(mock(IEventBus.class));
        try (MockedStatic<FMLJavaModLoadingContext> loading = mockStatic(FMLJavaModLoadingContext.class)) {
            loading.when(FMLJavaModLoadingContext::get).thenReturn(context);
            Class.forName("com.huanghuang.rsintegration.RSIntegrationMod");
        }
    }

    @Test
    void machineWorkRenewsTimeoutButRepeatedOrRegressedProgressDoesNot() throws Exception {
        Fixture fixture = fixture();
        AtomicLong work = new AtomicLong();
        when(fixture.delegate.machineOperation().observe(fixture.player.serverLevel())).thenAnswer(call ->
                new MachineOperation.CraftObservation(MachineOperation.CraftPhase.WORKING)
                        .withCompletedWork(work.get()));
        int timeoutTicks = RSIntegrationConfig.MULTIBLOCK_CRAFT_TIMEOUT_SECONDS.get() * 20;
        for (int progress = 1; progress <= 3; progress++) {
            set(fixture.runtime, "waitTicks", timeoutTicks);
            work.set(progress);
            assertFalse(fixture.chain.tick());
            assertEquals(0, field(fixture.runtime, "waitTicks"));
            verify(fixture.delegate.machineOperation(), never()).cleanup(any());
        }
        work.set(1L);
        assertFalse(fixture.chain.tick());
        assertEquals(1, field(fixture.runtime, "waitTicks"));
        work.set(3L);
        set(fixture.runtime, "waitTicks", timeoutTicks);
        assertTrue(fixture.chain.tick());
        assertEquals(AsyncCraftChain.State.ABORTED, fixture.chain.state());
        verify(fixture.delegate.machineOperation(), never()).cleanup(any());
    }

    @Test
    void incrementalCaptureRenewsTheIdleTimeoutUntilTheWholeBatchArrives() throws Exception {
        Fixture fixture = fixture();
        int timeoutTicks = RSIntegrationConfig.MULTIBLOCK_CRAFT_TIMEOUT_SECONDS.get() * 20;
        for (int count = 1; count < 3; count++) {
            set(fixture.runtime, "waitTicks", timeoutTicks);
            fixture.captured.add(new ItemStack(Items.DIAMOND));

            assertFalse(fixture.chain.tick());
            assertEquals(AsyncCraftChain.State.EXECUTING, fixture.chain.state());
            assertEquals(0, field(fixture.runtime, "waitTicks"));
            verify(fixture.delegate.machineOperation(), never()).cleanup(any());
            verify(fixture.capture, never()).drainAndClose();
        }
        assertFalse(fixture.chain.tick());
        assertEquals(1, field(fixture.runtime, "waitTicks"));

        fixture.captured.add(new ItemStack(Items.DIAMOND));
        assertTrue(fixture.chain.tick());
        verify(fixture.delegate.machineOperation()).cleanup(fixture.player);
        verify(fixture.capture).drainAndClose();
    }

    @Test
    void incrementalParallelWorkerCaptureRenewsTimeoutBeforeTheBatchCompletes() throws Exception {
        ParallelCraftGroup group = mock(ParallelCraftGroup.class);
        when(group.machineOperation()).thenReturn(mock(MachineOperation.class));
        when(group.outputSource()).thenReturn(mock(OperationOutputSource.class));
        Fixture fixture = fixture(group);
        when(group.machineOperation().observe(fixture.player.serverLevel())).thenReturn(
                new MachineOperation.CraftObservation(MachineOperation.CraftPhase.WORKING));
        when(group.getTotalOperations()).thenReturn(1024);
        when(group.getRunningOperations()).thenReturn(64);
        when(group.getExpectedOutput()).thenReturn(new ItemStack(Items.DIAMOND));
        AtomicLong production = new AtomicLong();
        when(group.getProductionSequence()).thenAnswer(call -> production.get());

        int timeoutTicks = RSIntegrationConfig.MULTIBLOCK_CRAFT_TIMEOUT_SECONDS.get() * 20;
        for (int output = 0; output < 63; output++) {
            set(fixture.runtime, "waitTicks", timeoutTicks);
            production.incrementAndGet();
            assertFalse(fixture.chain.tick());
            assertEquals(0, field(fixture.runtime, "waitTicks"));
            assertEquals(0, group.getCompletedOperations());
        }
        assertFalse(fixture.chain.tick());
        assertEquals(1, field(fixture.runtime, "waitTicks"));
        verify(group.machineOperation(), never()).cleanup(any());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3})
    void shutdownSettlesOnlyACompleteBatchAndPreservesPartialCapturedOutputs(
            int capturedCount) throws Exception {
        Fixture fixture = fixture();
        fixture.captured.add(new ItemStack(Items.DIAMOND, capturedCount));
        if (capturedCount == 2) doThrow(new IllegalStateException("拒绝取消通知"))
                .when(fixture.delegate).prepareCancellation(isNull(), eq(CancelReason.SERVER_STOP));

        fixture.chain.abortForServerStop();
        fixture.chain.abortForServerStop();

        var order = inOrder(fixture.delegate.outputSource(), fixture.delegate.machineOperation());
        ServerLevel level = fixture.player.serverLevel();
        order.verify(fixture.delegate.outputSource()).transferAvailable(level);
        order.verify(fixture.delegate.machineOperation()).advance(level);
        order.verify(fixture.delegate.machineOperation()).observe(level);
        verify(fixture.delegate.machineOperation()).advance(level);
        assertEquals(AsyncCraftChain.State.ABORTED, fixture.chain.state());
        if (capturedCount == 3) {
            verify(fixture.delegate.machineOperation()).cleanup(fixture.player);
            verify(fixture.delegate.machineOperation(), never()).recover(any(), anyString());
        } else {
            verify(fixture.delegate).prepareCancellation(isNull(), eq(CancelReason.SERVER_STOP));
            verify(fixture.delegate.machineOperation(), never()).cleanup(any());
            verify(fixture.delegate.machineOperation()).recover(isNull(), eq("Server stopping"));
        }
        assertEquals(capturedCount, fixture.returned.stream().mapToInt(ItemStack::getCount).sum());
        verify(fixture.capture).drainAndClose();
    }

    @Test
    void shutdownReturnsPreviouslyReceivedItemsEvenWhenMachineCleanupThrows() throws Exception {
        Fixture fixture = fixture();
        fixture.captured.add(new ItemStack(Items.DIAMOND, 3));
        try (var reception = TaskOutputReception.open()) {
            var key = TaskOutputReception.resource(new ItemStack(Items.STICK));
            reception.prepare(List.of()).collect(key, 2);
            Field lifecycle = CraftNodeRuntime.class.getDeclaredField("operation");
            lifecycle.setAccessible(true);
            lifecycle.set(fixture.runtime, new OperationLifecycle(fixture.delegate, reception));
            var machine = fixture.delegate.machineOperation();
            doThrow(new IllegalStateException("清理失败")).when(machine).cleanup(fixture.player);
            fixture.chain.abortForServerStop();
            fixture.chain.abortForServerStop();
            assertEquals(5, fixture.returned.stream().mapToInt(ItemStack::getCount).sum());
            verify(fixture.delegate.machineOperation(), never()).recover(any(), anyString());
        }
    }

    private static Fixture fixture() throws Exception {
        return fixture(mock(IBatchDelegate.class));
    }

    @SuppressWarnings("unchecked")
    private static Fixture fixture(IBatchDelegate delegate) throws Exception {
        UUID owner = UUID.randomUUID();
        MinecraftServer server = mock(MinecraftServer.class);
        RecipeManager recipes = mock(RecipeManager.class);
        when(server.getRecipeManager()).thenReturn(recipes);
        when(recipes.byKey(any())).thenReturn(Optional.empty());
        ServerLevel level = mock(ServerLevel.class);
        PlayerList players = mock(PlayerList.class);
        ServerPlayer player = mock(ServerPlayer.class);
        when(server.getPlayerList()).thenReturn(players);
        when(server.overworld()).thenReturn(level);
        when(players.getPlayer(owner)).thenReturn(player);
        when(player.serverLevel()).thenReturn(level);
        when(player.getServer()).thenReturn(server);
        when(player.getName()).thenReturn(Component.literal("test-player"));

        List<ItemStack> returned = new ArrayList<>();
        CraftStorageEndpoint endpoint = mock(CraftStorageEndpoint.class);
        when(endpoint.isAvailable()).thenReturn(true);
        when(endpoint.insert(eq(player), any(), eq(false))).thenAnswer(call -> {
            ItemStack stack = call.getArgument(1);
            returned.add(stack.copy());
            return StorageOperationResult.inserted(
                    StorageOperationMode.PERFORM, stack, ItemStack.EMPTY);
        });

        List<ItemStack> captured = new ArrayList<>();
        CraftOutputInterceptor.CaptureHandle capture = mock(CraftOutputInterceptor.CaptureHandle.class);
        when(capture.hasCaptured()).thenAnswer(call -> !captured.isEmpty());
        when(capture.snapshot()).thenAnswer(call -> List.copyOf(captured));
        when(capture.drainAndClose()).thenAnswer(call -> {
            List<ItemStack> drained = List.copyOf(captured);
            captured.clear();
            return drained;
        });
        when(delegate.machineOperation()).thenReturn(mock(MachineOperation.class));
        when(delegate.machineOperation().observe(level)).thenReturn(
                new MachineOperation.CraftObservation(MachineOperation.CraftPhase.WORKING));
        when(delegate.getExpectedOutput()).thenReturn(new ItemStack(Items.DIAMOND));
        when(delegate.getExpectedProduction()).thenReturn(
                new IBatchDelegate.ExpectedProduction(new ItemStack(Items.DIAMOND), 3));
        when(delegate.validateExecutionContext(player)).thenReturn(true);
        var source = mock(OperationOutputSource.class);
        when(source.collectFinal(player)).thenReturn(OperationOutputSource.Handoff.physical(List.of(), null));
        when(delegate.outputSource()).thenReturn(source);
        CraftingResolver.ResolutionStep step = new CraftingResolver.ResolutionStep(
                new ResourceLocation("test", "batch"), ModType.GENERIC,
                new ResourceLocation("test", "batch"), List.of(), List.of(), false, 3);
        AsyncCraftChain chain = new AsyncCraftChain(owner, server, endpoint, TestCraftGraphs.terminal(step));
        set(chain, "state", AsyncCraftChain.State.EXECUTING);
        NodeId id = new NodeId(0);
        var runtime = new CraftNodeRuntime(id, "batch", delegate, null, null);
        runtime.setChainContext(chain.virtualInventory(), player);
        when(delegate.publishesDeclaredGraphOutputs()).thenReturn(true);
        runtime.attachOutputs(new NodeOutputAccumulator(List.of()));
        runtime.attachCapture(new CaptureSession(mock(CaptureLeaseRegistry.class), null, capture));
        ((Map<NodeId, CraftNodeRuntime>) field(chain, "nodeRuntimes")).put(id, runtime);
        var scheduler = (DagScheduler) field(chain, "graphScheduler");
        var executor = new ConcurrentNodeExecutor(scheduler, n -> ConcurrentNodeExecutor.StartResult.started(runtime),
                1, n -> false, chain::publishIncrementalGraphOutputs, chain::completeGraphNode, 1, 1);
        set(chain, "graphExecutor", executor);
        executor.tick();
        return new Fixture(chain, runtime, delegate, player, capture, captured, returned);
    }

    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private record Fixture(AsyncCraftChain chain, CraftNodeRuntime runtime, IBatchDelegate delegate, ServerPlayer player,
                           CraftOutputInterceptor.CaptureHandle capture,
                           List<ItemStack> captured, List<ItemStack> returned) {}
}
