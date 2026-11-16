package com.huanghuang.rsintegration.crafting;

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
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
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
            assertEquals(true, finish.invoke(fixture.chain, fixture.player));
            assertEquals(destination == OutputDestination.PLAYER_INVENTORY ? 3 : 0, deliveredToPlayer.get());
            assertEquals(destination == OutputDestination.RS_NETWORK ? 5 : 2, stored.get());
            assertEquals(0, otherTask.received(key), "最终写回不能被另一任务再次认领");
            assertTrue(reception.drain().isEmpty());
            assertTrue(fixture.chain.tick());
            assertEquals(5, deliveredToPlayer.get() + stored.get());
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
    void flatMachineWorkRenewsTimeoutButRepeatedOrRegressedProgressDoesNot() throws Exception {
        Fixture fixture = fixture();
        AtomicLong work = new AtomicLong();
        when(fixture.delegate.observeCraft(fixture.player.serverLevel())).thenAnswer(call ->
                new IBatchDelegate.CraftObservation(IBatchDelegate.CraftPhase.WORKING)
                        .withCompletedWork(work.get()));
        int timeoutTicks = RSIntegrationConfig.MULTIBLOCK_CRAFT_TIMEOUT_SECONDS.get() * 20;
        for (int progress = 1; progress <= 3; progress++) {
            set(fixture.chain, "waitTicks", timeoutTicks);
            work.set(progress);
            assertFalse(fixture.chain.tick());
            assertEquals(0, field(fixture.chain, "waitTicks"));
            verify(fixture.delegate, never()).onBatchFinished(any());
        }
        work.set(1L);
        assertFalse(fixture.chain.tick());
        assertEquals(1, field(fixture.chain, "waitTicks"));
        work.set(3L);
        set(fixture.chain, "waitTicks", timeoutTicks);
        assertTrue(fixture.chain.tick());
        assertEquals(AsyncCraftChain.State.ABORTED, fixture.chain.state());
        verify(fixture.delegate, never()).onBatchFinished(any());
    }

    @Test
    void incrementalFlatCaptureRenewsTheIdleTimeoutUntilTheWholeBatchArrives() throws Exception {
        Fixture fixture = fixture();
        int timeoutTicks = RSIntegrationConfig.MULTIBLOCK_CRAFT_TIMEOUT_SECONDS.get() * 20;
        for (int count = 1; count < 3; count++) {
            set(fixture.chain, "waitTicks", timeoutTicks);
            fixture.captured.add(new ItemStack(Items.DIAMOND));

            assertFalse(fixture.chain.tick());
            assertEquals(AsyncCraftChain.State.EXECUTING, fixture.chain.state());
            assertEquals(0, field(fixture.chain, "waitTicks"));
            verify(fixture.delegate, never()).onBatchFinished(any());
            verify(fixture.capture, never()).drainAndClose();
        }
        assertFalse(fixture.chain.tick());
        assertEquals(1, field(fixture.chain, "waitTicks"));

        fixture.captured.add(new ItemStack(Items.DIAMOND));
        assertFalse(fixture.chain.tick());
        verify(fixture.delegate).onBatchFinished(fixture.player);
        verify(fixture.capture).drainAndClose();
    }

    @Test
    void incrementalParallelWorkerCaptureRenewsFlatTimeoutBeforeTheBatchCompletes() throws Exception {
        Fixture fixture = fixture();
        ParallelCraftGroup group = mock(ParallelCraftGroup.class);
        when(group.observeCraft(fixture.player.serverLevel())).thenReturn(
                new IBatchDelegate.CraftObservation(IBatchDelegate.CraftPhase.WORKING));
        when(group.getTotalOperations()).thenReturn(1024);
        when(group.getRunningOperations()).thenReturn(64);
        when(group.getExpectedOutput()).thenReturn(new ItemStack(Items.DIAMOND));
        AtomicLong production = new AtomicLong();
        when(group.getProductionSequence()).thenAnswer(call -> production.get());
        set(fixture.chain, "currentDelegate", group);

        int timeoutTicks = RSIntegrationConfig.MULTIBLOCK_CRAFT_TIMEOUT_SECONDS.get() * 20;
        for (int output = 0; output < 63; output++) {
            set(fixture.chain, "waitTicks", timeoutTicks);
            production.incrementAndGet();
            assertFalse(fixture.chain.tick());
            assertEquals(0, field(fixture.chain, "waitTicks"));
            assertEquals(0, group.getCompletedOperations());
        }
        assertFalse(fixture.chain.tick());
        assertEquals(1, field(fixture.chain, "waitTicks"));
        verify(group, never()).onBatchFinished(any());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3})
    void shutdownSettlesOnlyACompleteBatchAndPreservesPartialCapturedOutputs(
            int capturedCount) throws Exception {
        Fixture fixture = fixture();
        fixture.captured.add(new ItemStack(Items.DIAMOND, capturedCount));

        fixture.chain.abortForServerStop();
        fixture.chain.abortForServerStop();

        assertEquals(AsyncCraftChain.State.ABORTED, fixture.chain.state());
        if (capturedCount == 3) {
            verify(fixture.delegate).onBatchFinished(fixture.player);
            verify(fixture.delegate, never()).onBatchFailed(any(), anyString());
        } else {
            verify(fixture.delegate, never()).onBatchFinished(any());
            verify(fixture.delegate).onBatchFailed(isNull(), eq("Server stopping"));
        }
        assertEquals(capturedCount, fixture.returned.stream().mapToInt(ItemStack::getCount).sum());
        verify(fixture.capture).drainAndClose();
    }

    private static Fixture fixture() throws Exception {
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
        IBatchDelegate delegate = mock(IBatchDelegate.class);
        when(delegate.observeCraft(level)).thenReturn(
                new IBatchDelegate.CraftObservation(IBatchDelegate.CraftPhase.WORKING));
        when(delegate.getExpectedOutput()).thenReturn(new ItemStack(Items.DIAMOND));
        when(delegate.getExpectedProduction()).thenReturn(
                new IBatchDelegate.ExpectedProduction(new ItemStack(Items.DIAMOND), 3));
        when(delegate.validateExecutionContext(player)).thenReturn(true);
        when(delegate.collectAllResults(player)).thenReturn(List.of());
        when(delegate.collectsPhysicalSecondaryOutputs()).thenReturn(true);
        CraftingResolver.ResolutionStep step = new CraftingResolver.ResolutionStep(
                new ResourceLocation("test", "batch"), ModType.GENERIC,
                new ResourceLocation("test", "batch"), List.of(), List.of(), false, 3);
        AsyncCraftChain chain = new AsyncCraftChain(owner, server, endpoint, List.of(step));
        set(chain, "state", AsyncCraftChain.State.EXECUTING);
        set(chain, "stepRemaining", 3);
        set(chain, "currentDelegate", delegate);
        set(chain, "captureHandle", capture);
        return new Fixture(chain, delegate, player, capture, captured, returned);
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

    private record Fixture(AsyncCraftChain chain, IBatchDelegate delegate, ServerPlayer player,
                           CraftOutputInterceptor.CaptureHandle capture,
                           List<ItemStack> captured, List<ItemStack> returned) {}
}
