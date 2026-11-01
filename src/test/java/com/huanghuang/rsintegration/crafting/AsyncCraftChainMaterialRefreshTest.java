package com.huanghuang.rsintegration.crafting;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.api.IntegrationResourceKey;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.crafting.graph.AllocationId;
import com.huanghuang.rsintegration.crafting.graph.ConcurrentNodeExecutor;
import com.huanghuang.rsintegration.crafting.graph.CraftNode;
import com.huanghuang.rsintegration.crafting.graph.CraftPlanGraph;
import com.huanghuang.rsintegration.crafting.graph.DagScheduler;
import com.huanghuang.rsintegration.crafting.graph.DemandRole;
import com.huanghuang.rsintegration.crafting.graph.InputDemand;
import com.huanghuang.rsintegration.crafting.graph.InputPortId;
import com.huanghuang.rsintegration.crafting.graph.MaterialAllocation;
import com.huanghuang.rsintegration.crafting.graph.MaterialBroker;
import com.huanghuang.rsintegration.crafting.graph.MaterialKey;
import com.huanghuang.rsintegration.crafting.graph.MaterialSource;
import com.huanghuang.rsintegration.crafting.graph.NodeId;
import com.huanghuang.rsintegration.crafting.graph.NodeOutputAccumulator;
import com.huanghuang.rsintegration.crafting.graph.OutputDeclaration;
import com.huanghuang.rsintegration.crafting.graph.OutputKind;
import com.huanghuang.rsintegration.crafting.graph.OutputPortId;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AsyncCraftChainMaterialRefreshTest extends BootstrapTest {
    private static final NodeId PRODUCER = new NodeId(0);
    private static final NodeId CONSUMER = new NodeId(1);
    private static final MaterialKey IRON = MaterialKey.of(new ItemStack(Items.IRON_INGOT));
    private static final OutputPortId PORT = new OutputPortId(PRODUCER, 0);
    private static final MaterialSource SOURCE = new MaterialSource.ProducerOutput(PORT);

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
    void emptyWorkerCallbacksDoNotRepeatScanOrSuppressProgress() throws Exception {
        Fixture fixture = new Fixture();
        CraftNodeRuntime runtime = mock(CraftNodeRuntime.class);
        when(runtime.drainIncrementalOutputs()).thenReturn(List.of());
        fixture.publish(runtime);
        clearInvocations(fixture.scheduler);
        for (int i = 0; i < 32; i++) fixture.publish(runtime);
        verify(fixture.scheduler, times(0)).refreshBlocked(any());
        ProgressWatchdog watchdog = (ProgressWatchdog) field(fixture.chain, "graphProgressWatchdog");
        watchdog.tick();
        when(runtime.consumeProgressSignal()).thenReturn(true);
        fixture.publish(runtime);
        assertEquals(0, watchdog.idleTicks());
        verify(fixture.scheduler, times(0)).refreshBlocked(any());
    }

    @Test
    void incrementalOutputUnlocksConsumerBeforeProducerCompletes() throws Exception {
        Fixture fixture = new Fixture();
        fixture.scheduler.claim(PRODUCER);
        CraftNodeRuntime runtime = mock(CraftNodeRuntime.class);
        NodeOutputAccumulator.Publication unit = new NodeOutputAccumulator.Publication(
                PORT, IRON, new ItemStack(Items.IRON_INGOT),
                IntegrationResourceKey.item(new ItemStack(Items.IRON_INGOT)));
        when(runtime.drainIncrementalOutputs()).thenReturn(List.of(unit), List.of(unit), List.of());
        fixture.publish(runtime);
        assertEquals(DagScheduler.NodeState.BLOCKED, fixture.scheduler.state(CONSUMER));
        fixture.publish(runtime);
        assertEquals(DagScheduler.NodeState.READY, fixture.scheduler.state(CONSUMER));
        assertEquals(DagScheduler.NodeState.RUNNING, fixture.scheduler.state(PRODUCER));
        fixture.publish(runtime);
        verify(fixture.scheduler, times(2)).refreshBlocked(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"release", "refund", "unused", "committed", "recovered"})
    void materialReturnWakesConsumerOnTickWithNoRunningWorker(String path) throws Exception {
        Fixture fixture = new Fixture();
        fixture.broker.publish(SOURCE, IRON, 2);
        MaterialBroker.ReservationToken token = fixture.broker.reserve(PRODUCER,
                List.of(new MaterialBroker.Request(SOURCE, IRON, 2)));
        if (List.of("refund", "committed", "recovered").contains(path)) fixture.broker.commit(token);
        fixture.tick();
        assertEquals(0, fixture.executor.runningCount());
        assertEquals(DagScheduler.NodeState.BLOCKED, fixture.scheduler.state(CONSUMER));
        switch (path) {
            case "release" -> fixture.broker.release(token);
            case "refund" -> fixture.broker.refund(token);
            case "unused" -> fixture.broker.releaseUnusedReservedFragments(token,
                    List.of(), List.of(new ItemStack(Items.IRON_INGOT, 2)));
            case "committed" -> fixture.broker.refundCommittedProducerFragments(token,
                    List.of(new ItemStack(Items.IRON_INGOT, 2)));
            case "recovered" -> fixture.broker.refundRecoveredProducerFragments(token,
                    List.of(new ItemStack(Items.IRON_INGOT, 2)));
            default -> throw new AssertionError(path);
        }
        fixture.tick();
        assertEquals(DagScheduler.NodeState.READY, fixture.scheduler.state(CONSUMER));
        fixture.tick();
        verify(fixture.scheduler, times(2)).refreshBlocked(any());
    }

    @Test
    void stoppingSchedulerDoesNotRefreshAfterPublication() throws Exception {
        Fixture fixture = new Fixture();
        fixture.scheduler.stopScheduling();
        fixture.broker.publish(SOURCE, IRON, 2);
        fixture.publish(mock(CraftNodeRuntime.class));
        verify(fixture.scheduler, times(0)).refreshBlocked(any());
        assertEquals(DagScheduler.NodeState.CANCELLED, fixture.scheduler.state(CONSUMER));
    }

    private static final class Fixture {
        final AsyncCraftChain chain;
        final MaterialBroker broker;
        final DagScheduler scheduler;
        final ConcurrentNodeExecutor executor;

        Fixture() throws Exception {
            MinecraftServer server = mock(MinecraftServer.class);
            PlayerList players = mock(PlayerList.class);
            when(server.getPlayerList()).thenReturn(players);
            when(players.getPlayer(any())).thenReturn(mock(ServerPlayer.class));
            InputPortId input = new InputPortId(CONSUMER, 0);
            CraftNode producer = new CraftNode(PRODUCER, new ResourceLocation("test", "producer"),
                    ModType.CUSTOM_GUI.id(), null, 1, List.of(), List.of(), false, null, null,
                    List.of(), List.of(OutputDeclaration.item(PORT, IRON, 2, OutputKind.PRIMARY)));
            CraftNode consumer = new CraftNode(CONSUMER, new ResourceLocation("test", "consumer"),
                    ModType.CUSTOM_GUI.id(), null, 1, List.of(), List.of(), false, null, null,
                    List.of(new InputDemand(input, Ingredient.of(Items.IRON_INGOT), 2,
                            DemandRole.CONSUMED, new ItemStack(Items.IRON_INGOT))), List.of());
            CraftPlanGraph graph = new CraftPlanGraph(1, List.of(producer, consumer),
                    List.of(new MaterialAllocation(new AllocationId(0), input, SOURCE, IRON, 2)),
                    List.of(), List.of(), List.of(PRODUCER, CONSUMER));
            chain = new AsyncCraftChain(UUID.randomUUID(), server, null, graph);
            broker = (MaterialBroker) field(chain, "graphMaterials");
            scheduler = spy((DagScheduler) field(chain, "graphScheduler"));
            set(chain, "graphScheduler", scheduler);
            // 跳过首 tick 的发包；仍通过真正的图 tick 验证无 worker 时的材料唤醒。
            executor = new ConcurrentNodeExecutor(scheduler, nodeId -> null, 1);
            set(chain, "graphExecutor", executor);
            set(chain, "state", AsyncCraftChain.State.EXECUTING);
        }

        void tick() {
            assertFalse(chain.tick(new VanillaCraftingTickBudget(1).allowance(0), () -> false));
        }

        void publish(CraftNodeRuntime runtime) throws Exception {
            Method publish = AsyncCraftChain.class.getDeclaredMethod("publishIncrementalGraphOutputs",
                    NodeId.class, ConcurrentNodeExecutor.Worker.class);
            publish.setAccessible(true);
            publish.invoke(chain, PRODUCER, runtime);
        }
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
}
