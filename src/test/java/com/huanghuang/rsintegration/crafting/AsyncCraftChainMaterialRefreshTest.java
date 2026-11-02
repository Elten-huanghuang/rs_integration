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
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
        verify(fixture.scheduler, times(0)).refreshBlocked(any(BitSet.class), any());
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
        BitSet candidates = new BitSet();
        GraphMaterialWakeIndex wakeIndex =
                (GraphMaterialWakeIndex) field(fixture.chain, "graphMaterialWakeIndex");
        wakeIndex.include(SOURCE, candidates);
        assertEquals(new BitSet(), candidates);
        fixture.publish(runtime);
        verify(fixture.scheduler, times(1)).refreshBlocked(any());
        verify(fixture.scheduler, times(1)).refreshBlocked(any(BitSet.class), any());
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
        verify(fixture.scheduler, times(1)).refreshBlocked(any());
        verify(fixture.scheduler, times(1)).refreshBlocked(any(BitSet.class), any());
    }

    @Test
    void stoppingSchedulerDoesNotRefreshAfterPublication() throws Exception {
        Fixture fixture = new Fixture();
        fixture.scheduler.stopScheduling();
        fixture.broker.publish(SOURCE, IRON, 2);
        fixture.publish(mock(CraftNodeRuntime.class));
        verify(fixture.scheduler, times(0)).refreshBlocked(any());
        verify(fixture.scheduler, times(0)).refreshBlocked(any(BitSet.class), any());
        assertEquals(DagScheduler.NodeState.CANCELLED, fixture.scheduler.state(CONSUMER));
    }

    @Test
    void onlyChangedSourcesAreCheckedAndRepeatedRequestsAreDeduplicated() throws Exception {
        Fixture fixture = new Fixture(true);
        fixture.refresh();
        fixture.requests.checked.clear();
        fixture.broker.publish(source(10), IRON, 1);
        fixture.broker.publish(source(10), IRON, 1);
        fixture.refresh();
        assertEquals(List.of(new NodeId(30), new NodeId(40)), fixture.requests.checked);
        assertEquals(DagScheduler.NodeState.BLOCKED, fixture.scheduler.state(new NodeId(30)));
        assertEquals(DagScheduler.NodeState.BLOCKED, fixture.scheduler.state(new NodeId(40)));
        fixture.requests.checked.clear();
        fixture.refresh();
        assertEquals(List.of(), fixture.requests.checked);
        fixture.broker.publish(source(99), IRON, 8);
        fixture.refresh();
        assertEquals(List.of(), fixture.requests.checked);
    }

    @Test
    void changedSourcesMergeInTopologyOrderAndKeepCompleteMultiSourceChecks() throws Exception {
        Fixture fixture = new Fixture(true);
        fixture.scheduler.claim(new NodeId(10));
        fixture.scheduler.claim(new NodeId(20));
        fixture.refresh();
        fixture.requests.checked.clear();
        fixture.broker.publish(source(20), IRON, 4);
        fixture.broker.publish(source(10), IRON, 6);
        fixture.refresh();
        List<NodeId> expected = List.of(new NodeId(50), new NodeId(30), new NodeId(40));
        assertEquals(expected, fixture.requests.checked);
        assertEquals(expected, fixture.scheduler.peekReady(10));
        assertEquals(DagScheduler.NodeState.RUNNING, fixture.scheduler.state(new NodeId(10)));
        assertEquals(DagScheduler.NodeState.RUNNING, fixture.scheduler.state(new NodeId(20)));
    }

    @Test
    void consumerWakesWhenLastRequiredSourceArrivesAndShortagesStayAccurate() throws Exception {
        Fixture fixture = new Fixture(true);
        fixture.refresh();
        fixture.broker.publish(source(10), IRON, 6);
        fixture.refresh();
        List<MaterialBroker.Request> requests = fixture.requests.get(new NodeId(30));
        assertEquals(DagScheduler.NodeState.BLOCKED, fixture.scheduler.state(new NodeId(30)));
        assertEquals(List.of(new MaterialBroker.Request(source(20), IRON, 2)),
                fixture.broker.shortages(requests));
        fixture.requests.checked.clear();
        fixture.broker.publish(source(20), IRON, 4);
        fixture.refresh();
        assertEquals(List.of(new NodeId(50), new NodeId(30)), fixture.requests.checked);
        assertEquals(DagScheduler.NodeState.READY, fixture.scheduler.state(new NodeId(30)));
        assertEquals(List.of(), fixture.broker.shortages(requests));
    }

    @Test
    void anotherMaterialFromSameSourceDoesNotFalselyWakeConsumer() throws Exception {
        Fixture fixture = new Fixture();
        fixture.broker.publish(SOURCE, IRON, 1);
        fixture.refresh();
        fixture.requests.checked.clear();
        MaterialKey gold = MaterialKey.of(new ItemStack(Items.GOLD_INGOT));
        fixture.broker.publish(SOURCE, gold, 8);
        fixture.refresh();
        assertEquals(List.of(CONSUMER), fixture.requests.checked);
        assertEquals(DagScheduler.NodeState.BLOCKED, fixture.scheduler.state(CONSUMER));
        List<MaterialBroker.Request> requests = fixture.requests.get(CONSUMER);
        assertEquals(List.of(new MaterialBroker.Request(SOURCE, IRON, 1)),
                fixture.broker.shortages(requests));
        fixture.broker.publish(SOURCE, IRON, 1);
        fixture.refresh();
        assertEquals(DagScheduler.NodeState.READY, fixture.scheduler.state(CONSUMER));
        assertEquals(List.of(), fixture.broker.shortages(requests));
    }

    @Test
    void candidateNotificationsSurviveFailedReadinessCheck() throws Exception {
        Fixture fixture = new Fixture();
        fixture.refresh();
        fixture.broker.publish(SOURCE, IRON, 2);
        fixture.requests.failNextCheck = true;
        assertThrows(InvocationTargetException.class, fixture::refresh);
        assertEquals(DagScheduler.NodeState.BLOCKED, fixture.scheduler.state(CONSUMER));
        fixture.refresh();
        assertEquals(DagScheduler.NodeState.READY, fixture.scheduler.state(CONSUMER));
    }

    @Test
    void selectiveRefreshMatchesFullRefreshAcrossPublicationOrders() throws Exception {
        for (int sequence = 0; sequence < 32; sequence++) {
            Fixture selective = new Fixture(true);
            Fixture reference = new Fixture(true);
            selective.refresh();
            reference.refresh();
            for (int step = 0; step < 5; step++) {
                MaterialSource changed = source((sequence & (1 << step)) == 0 ? 10 : 20);
                selective.broker.publish(changed, IRON, 1);
                reference.broker.publish(changed, IRON, 1);
                selective.refresh();
                reference.scheduler.refreshBlocked(nodeId -> reference.broker.canReserve(
                        reference.requests.getOrDefault(nodeId, List.of())));
                assertEquals(reference.scheduler.stateSnapshot(), selective.scheduler.stateSnapshot());
                assertEquals(reference.scheduler.peekReady(10), selective.scheduler.peekReady(10));
                for (NodeId nodeId : List.of(new NodeId(50), new NodeId(30), new NodeId(40))) {
                    assertEquals(reference.broker.shortages(reference.requests.get(nodeId)),
                            selective.broker.shortages(selective.requests.get(nodeId)));
                }
            }
        }
    }

    private static MaterialSource source(int producer) {
        return new MaterialSource.ProducerOutput(new OutputPortId(new NodeId(producer), 0));
    }

    private static CraftPlanGraph branchingGraph() {
        List<NodeId> order = List.of(new NodeId(10), new NodeId(20),
                new NodeId(50), new NodeId(30), new NodeId(40));
        Map<NodeId, List<MaterialSource>> sources = Map.of(
                new NodeId(50), List.of(source(20)),
                new NodeId(30), List.of(source(10), source(20)),
                new NodeId(40), List.of(source(10), source(10)));
        List<CraftNode> nodes = new ArrayList<>();
        List<MaterialAllocation> allocations = new ArrayList<>();
        for (NodeId nodeId : order) {
            List<InputDemand> inputs = new ArrayList<>();
            for (MaterialSource source : sources.getOrDefault(nodeId, List.of())) {
                InputPortId port = new InputPortId(nodeId, inputs.size());
                inputs.add(new InputDemand(port, Ingredient.of(Items.IRON_INGOT), 2,
                        DemandRole.CONSUMED, new ItemStack(Items.IRON_INGOT)));
                allocations.add(new MaterialAllocation(new AllocationId(allocations.size()),
                        port, source, IRON, 2));
            }
            List<OutputDeclaration> outputs = inputs.isEmpty()
                    ? List.of(OutputDeclaration.item(new OutputPortId(nodeId, 0), IRON, 16, OutputKind.PRIMARY))
                    : List.of();
            nodes.add(new CraftNode(nodeId, new ResourceLocation("test", "node_" + nodeId.value()),
                    ModType.CUSTOM_GUI.id(), null, 1, List.of(), List.of(), false, null, null,
                    inputs, outputs));
        }
        return new CraftPlanGraph(1, nodes, allocations, List.of(), List.of(), order);
    }

    private static final class CountingRequests extends HashMap<NodeId, List<MaterialBroker.Request>> {
        final List<NodeId> checked = new ArrayList<>();
        boolean failNextCheck;

        @Override
        public List<MaterialBroker.Request> getOrDefault(Object key, List<MaterialBroker.Request> fallback) {
            if (failNextCheck) {
                failNextCheck = false;
                throw new IllegalStateException("retry readiness");
            }
            checked.add((NodeId) key);
            return super.getOrDefault(key, fallback);
        }
    }

    private static final class Fixture {
        final AsyncCraftChain chain;
        final MaterialBroker broker;
        final DagScheduler scheduler;
        final ConcurrentNodeExecutor executor;
        final CountingRequests requests = new CountingRequests();

        Fixture() throws Exception {
            this(false);
        }

        @SuppressWarnings("unchecked")
        Fixture(boolean branching) throws Exception {
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
            if (branching) graph = branchingGraph();
            chain = new AsyncCraftChain(UUID.randomUUID(), server, null, graph);
            requests.putAll((Map<NodeId, List<MaterialBroker.Request>>) field(chain, "graphRequests"));
            set(chain, "graphRequests", requests);
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

        void refresh() throws Exception {
            publish(mock(CraftNodeRuntime.class));
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
