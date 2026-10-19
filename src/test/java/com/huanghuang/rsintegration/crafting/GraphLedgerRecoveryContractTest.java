package com.huanghuang.rsintegration.crafting;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.crafting.batch.AbstractBatchDelegate;
import com.huanghuang.rsintegration.crafting.batch.IBatchDelegate;
import com.huanghuang.rsintegration.crafting.batch.OperationStartContext;
import com.huanghuang.rsintegration.crafting.graph.AllocationId;
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
import com.huanghuang.rsintegration.crafting.graph.NodeAdmissionCoordinator;
import com.huanghuang.rsintegration.crafting.graph.NodeId;
import com.huanghuang.rsintegration.crafting.graph.OutputDeclaration;
import com.huanghuang.rsintegration.crafting.graph.OutputKind;
import com.huanghuang.rsintegration.crafting.graph.OutputPortId;
import com.huanghuang.rsintegration.crafting.graph.RootAllocation;
import com.huanghuang.rsintegration.crafting.graph.RootDemand;
import com.huanghuang.rsintegration.network.binding.AltarBindingRegistry.BoundMachine;
import com.huanghuang.rsintegration.storage.StorageBackendId;
import com.huanghuang.rsintegration.storage.StorageItemKey;
import com.huanghuang.rsintegration.storage.StorageOperationMode;
import com.huanghuang.rsintegration.storage.StorageOperationResult;
import com.huanghuang.rsintegration.storage.StorageReference;
import com.huanghuang.rsintegration.storage.StorageSession;
import com.huanghuang.rsintegration.storage.StorageSnapshot;
import com.huanghuang.rsintegration.storage.StorageSnapshotResult;
import com.huanghuang.rsintegration.storage.StoredItem;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 联合验证图资产交接与物理提取退款，存储替身会实际增减库存。 */
class GraphLedgerRecoveryContractTest extends BootstrapTest {
    private static final NodeId PRODUCER = new NodeId(0);
    private static final NodeId CONSUMER = new NodeId(1);
    private static final ResourceLocation RECIPE = new ResourceLocation("test", "consumer");
    private static final Ingredient IRON = Ingredient.of(Items.IRON_INGOT);
    private static final Ingredient GOLD = Ingredient.of(Items.GOLD_INGOT);

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

    enum FailurePoint {
        BEFORE_RESERVATION,
        AFTER_RESERVATION,
        AFTER_COMMIT,
        PARTIAL_EXTRACTION
    }

    @ParameterizedTest
    @EnumSource(FailurePoint.class)
    void privateLedgerStartFailureDoesNotRefundExternalizedProducerClaimsTwice(
            FailurePoint failurePoint) throws Exception {
        Fixture fixture = new Fixture();
        ExtractionLedger privateLedger = new ExtractionLedger();
        ExtractionLedger nodeLedger = new ExtractionLedger();
        IBatchDelegate delegate = mock(IBatchDelegate.class);
        when(delegate.startOperation(any())).thenAnswer(call -> {
            OperationStartContext context = call.getArgument(0);
            assertEquals(OperationStartContext.MaterialOwnership.DELEGATE_EXTRACTED,
                    context.materialOwnership());
            assertSame(nodeLedger, context.ledger());
            assertEquals(MaterialBroker.ReservationState.SETTLED,
                    fixture.broker.state(fixture.admission.materialToken()));
            assertEquals(0, fixture.broker.heldBy(CONSUMER));
            assertAmounts(List.of(tagged("first", 2), tagged("second", 3), gold(2)),
                    fixture.stock);
            if (failurePoint == FailurePoint.BEFORE_RESERVATION) return false;

            assertEquals(5, privateLedger.reserveFromEndpoint(
                    IRON, 5, fixture.endpoint, fixture.player).getCount());
            assertEquals(2, privateLedger.reserveFromEndpoint(
                    GOLD, 2, fixture.endpoint, fixture.player).getCount());
            if (failurePoint == FailurePoint.AFTER_RESERVATION) return false;
            if (failurePoint == FailurePoint.PARTIAL_EXTRACTION) fixture.extractionLimit = 2;

            assertEquals(failurePoint == FailurePoint.AFTER_COMMIT,
                    privateLedger.commit(null, fixture.player));
            assertEquals(failurePoint == FailurePoint.AFTER_COMMIT
                            ? ExtractionLedger.State.COMMITTED : ExtractionLedger.State.ROLLED_BACK,
                    privateLedger.state());
            return false;
        });
        doAnswer(call -> {
            if (privateLedger.isCommitted()) privateLedger.refundCommitted(null, fixture.player);
            else privateLedger.rollback(fixture.player);
            privateLedger.close();
            return null;
        }).when(delegate).onBatchFailed(isNull(), anyString());

        CraftNodeRuntime runtime = fixture.dispatchPrivate(delegate, nodeLedger);
        assertEquals("delegate rejected private-ledger graph dispatch", runtime.failureReason());
        assertEquals(OperationExecutionKernel.TerminalClass.IN_FLIGHT, runtime.operationTerminalClass());
        assertTrue(nodeLedger.isCommitted());
        assertEquals(0, nodeLedger.size());
        if (failurePoint == FailurePoint.AFTER_COMMIT) assertTrue(fixture.stock.isEmpty());

        fixture.cleanupTwice();
        privateLedger.refundCommitted(null, fixture.player);
        privateLedger.close();

        assertEquals(ExtractionLedger.State.ROLLED_BACK, privateLedger.state());
        assertEquals(MaterialBroker.ReservationState.SETTLED,
                fixture.broker.state(fixture.admission.materialToken()));
        assertAmounts(List.of(tagged("first", 2), tagged("second", 3), gold(2)), fixture.stock);
        assertAmounts(List.of(tagged("first", 1)), fixture.broker.drainAvailableProducerAssets());
        assertTrue(fixture.broker.drainAvailableProducerAssets().isEmpty());
        assertEquals(0, fixture.broker.available(fixture.initialSource, fixture.goldKey));
        assertEquals(0, fixture.broker.heldBy(CONSUMER));
        verify(delegate).onBatchFailed(isNull(), anyString());
        verify(fixture.session, times(switch (failurePoint) {
            case BEFORE_RESERVATION, AFTER_RESERVATION -> 0;
            case AFTER_COMMIT -> 2;
            case PARTIAL_EXTRACTION -> 1;
        })).extractMatching(eq(fixture.player), any(), anyLong(), eq(false));
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 5})
    void sharedLedgerAndBrokerRefundOnlyTheirOwnPhysicallyRecoveredFragments(
            int recoveredProducerCount) throws Exception {
        Fixture fixture = new Fixture();
        ExtractionLedger nodeLedger = new ExtractionLedger();
        assertEquals(2, nodeLedger.reserveFromEndpoint(
                GOLD, 2, fixture.endpoint, fixture.player).getCount());
        assertTrue(nodeLedger.commit(null, fixture.player));
        assertTrue(fixture.stock.isEmpty());
        fixture.admissions.commit(fixture.admission);

        boolean fullyRecovered = recoveredProducerCount == 5;
        List<ItemStack> recovered = new ArrayList<>();
        if (fullyRecovered) recovered.add(tagged("first", 2));
        recovered.add(tagged("second", fullyRecovered ? 3 : 2));
        recovered.add(gold(fullyRecovered ? 2 : 1));
        AbstractBatchDelegate delegate = mock(AbstractBatchDelegate.class);
        when(delegate.physicalFailureCleanupCompleted()).thenReturn(true);
        when(delegate.failureRecoveredInputs()).thenReturn(recovered);
        OperationExecutionKernel.Session operation = fixture.operationKernel.prepareLogical();
        assertTrue(operation.commit(() -> nodeLedger.commit(null, fixture.player)));
        assertFalse(operation.tryStart(() -> false));
        CraftNodeRuntime runtime = new CraftNodeRuntime(CONSUMER, RECIPE.toString(),
                delegate, nodeLedger, fixture.admission, operation);
        runtime.setChainContext(new ArrayList<>(), fixture.player);
        runtime.markDispatched();
        runtime.markStartFailed("machine rejected start after accepting materials");
        fixture.runtimes.put(CONSUMER, runtime);

        fixture.cleanupTwice();
        nodeLedger.refundCommitted(null, fixture.player);

        assertEquals(ExtractionLedger.State.ROLLED_BACK, nodeLedger.state());
        assertEquals(MaterialBroker.ReservationState.SETTLED,
                fixture.broker.state(fixture.admission.materialToken()));
        assertEquals(0, fixture.broker.available(fixture.initialSource, fixture.goldKey));
        assertEquals(0, fixture.broker.heldBy(CONSUMER));
        assertAmounts(List.of(gold(fullyRecovered ? 2 : 1)), fixture.stock);
        List<ItemStack> producerRefund = fixture.broker.drainAvailableProducerAssets();
        List<ItemStack> expectedProducer = new ArrayList<>();
        expectedProducer.add(tagged("first", fullyRecovered ? 3 : 1));
        expectedProducer.add(tagged("second", fullyRecovered ? 3 : 2));
        assertAmounts(expectedProducer, producerRefund);
        assertTrue(fixture.broker.drainAvailableProducerAssets().isEmpty());
        verify(delegate).onBatchFailed(isNull(), anyString());
        verify(fixture.session).insert(eq(fixture.player), any(), eq(false));
    }

    private static final class Fixture {
        final StorageBackendId backend = new StorageBackendId("test");
        final List<ItemStack> stock = new ArrayList<>(List.of(gold(2)));
        final StorageSession session = mock(StorageSession.class, CALLS_REAL_METHODS);
        final CraftStorageEndpoint endpoint = () -> session;
        final ServerPlayer player = mock(ServerPlayer.class);
        final MaterialKey goldKey = MaterialKey.of(gold(1));
        final MaterialSource.InitialPool initialSource = new MaterialSource.InitialPool(goldKey);
        final AsyncCraftChain chain;
        final MaterialBroker broker;
        final NodeAdmissionCoordinator admissions;
        final NodeAdmissionCoordinator.Admission admission;
        final Map<NodeId, CraftNodeRuntime> runtimes;
        final OperationExecutionKernel operationKernel;
        int extractionLimit = Integer.MAX_VALUE;

        @SuppressWarnings("unchecked")
        Fixture() throws Exception {
            MinecraftServer server = mock(MinecraftServer.class);
            Field serverField = ServerPlayer.class.getField("server");
            serverField.setAccessible(true);
            serverField.set(player, server);
            when(player.getUUID()).thenReturn(UUID.randomUUID());
            when(session.reference()).thenReturn(new StorageReference(backend, "joint-recovery"));
            when(session.snapshotItems(player)).thenAnswer(call -> StorageSnapshotResult.success(
                    new StorageSnapshot(backend, stock.stream().filter(stack -> !stack.isEmpty())
                            .map(stack -> new StoredItem(StorageItemKey.fromItemStack(backend, stack),
                                    stack.getCount())).toList())));
            doAnswer(call -> {
                ItemStack stack = call.getArgument(1);
                stock.add(stack.copy());
                return StorageOperationResult.inserted(
                        StorageOperationMode.PERFORM, stack, ItemStack.EMPTY);
            }).when(session).insert(eq(player), any(), eq(false));
            when(session.extractMatching(eq(player), any(), anyLong(), eq(false)))
                    .thenAnswer(call -> extract(call.getArgument(1), call.getArgument(2)));

            CraftPlanGraph graph = graph();
            chain = new AsyncCraftChain(player.getUUID(), server, endpoint, graph);
            broker = (MaterialBroker) field(chain, "graphMaterials");
            admissions = (NodeAdmissionCoordinator) field(chain, "graphAdmissions");
            runtimes = (Map<NodeId, CraftNodeRuntime>) field(chain, "nodeRuntimes");
            operationKernel = (OperationExecutionKernel) field(chain, "operationKernel");
            for (OutputDeclaration output : graph.nodes().get(0).outputs()) {
                broker.publishActual(new MaterialSource.ProducerOutput(output.id()),
                        output.material(), output.material().toStack(output.quantity()));
            }
            DagScheduler scheduler = (DagScheduler) field(chain, "graphScheduler");
            scheduler.claim(PRODUCER);
            scheduler.succeed(PRODUCER);
            scheduler.claim(CONSUMER);
            admission = admissions.tryAdmitClaimed(new NodeAdmissionCoordinator.Candidate(CONSUMER,
                    graph.allocations().stream().map(allocation -> new MaterialBroker.Request(
                            allocation.source(), allocation.material(), allocation.quantity())).toList()));
            assertNotNull(admission);
        }

        private StorageOperationResult extract(Ingredient ingredient, long requested) {
            int remaining = (int) Math.min(requested, extractionLimit);
            List<ItemStack> extracted = new ArrayList<>();
            for (ItemStack stack : stock) {
                if (remaining <= 0) break;
                if (stack.isEmpty() || !ingredient.test(stack)) continue;
                int take = Math.min(remaining, stack.getCount());
                extracted.add(stack.copyWithCount(take));
                stack.shrink(take);
                remaining -= take;
            }
            stock.removeIf(ItemStack::isEmpty);
            return StorageOperationResult.extracted(StorageOperationMode.PERFORM, requested, extracted);
        }

        CraftNodeRuntime dispatchPrivate(IBatchDelegate delegate, ExtractionLedger nodeLedger)
                throws Exception {
            // 通过真实派发入口检查交接顺序，不在测试里复制 materialize/settle。
            Class<?> preparedType = Arrays.stream(AsyncCraftChain.class.getDeclaredClasses())
                    .filter(type -> type.getSimpleName().equals("PreparedGraphNode")).findFirst().orElseThrow();
            Constructor<?> constructor = preparedType.getDeclaredConstructor(
                    CraftingResolver.ResolutionStep.class, IBatchDelegate.class, List.class,
                    int.class, boolean.class);
            constructor.setAccessible(true);
            Object prepared = constructor.newInstance(new CraftingResolver.ResolutionStep(
                            RECIPE, ModType.CUSTOM_GUI, new ResourceLocation("test", "machine")),
                    delegate, List.of(new BoundMachine(new ResourceLocation("test", "dimension"),
                            BlockPos.ZERO, ModType.CUSTOM_GUI, null)), 1, false);
            Method dispatch = AsyncCraftChain.class.getDeclaredMethod("dispatchPrivateLedgerGraphNode",
                    NodeId.class, preparedType, IBatchDelegate.class, ServerPlayer.class,
                    NodeAdmissionCoordinator.Admission.class, ExtractionLedger.class);
            dispatch.setAccessible(true);
            dispatch.invoke(chain, CONSUMER, prepared, delegate, player, admission, nodeLedger);
            CraftNodeRuntime runtime = runtimes.get(CONSUMER);
            assertNotNull(runtime, "派发应进入真实 runtime，而非因资源不足提前返回");
            return runtime;
        }

        void cleanupTwice() throws Exception {
            Method cleanup = AsyncCraftChain.class.getDeclaredMethod(
                    "cleanupGraphNodes", ServerPlayer.class, String.class);
            cleanup.setAccessible(true);
            cleanup.invoke(chain, player, "joint recovery test");
            cleanup.invoke(chain, player, "repeated cleanup");
            assertTrue(runtimes.isEmpty());
            assertTrue(((List<?>) field(chain, "virtualInventory")).isEmpty());
        }

        private CraftPlanGraph graph() {
            MaterialKey first = MaterialKey.of(tagged("first", 1));
            MaterialKey second = MaterialKey.of(tagged("second", 1));
            MaterialKey diamond = MaterialKey.of(new ItemStack(Items.DIAMOND));
            OutputPortId firstPort = new OutputPortId(PRODUCER, 0);
            OutputPortId secondPort = new OutputPortId(PRODUCER, 1);
            OutputPortId resultPort = new OutputPortId(CONSUMER, 0);
            InputPortId ironInput = new InputPortId(CONSUMER, 0);
            InputPortId goldInput = new InputPortId(CONSUMER, 1);
            CraftNode producer = new CraftNode(PRODUCER, new ResourceLocation("test", "producer"),
                    ModType.GENERIC.id(), null, 1, List.of(), List.of(), false, null, null, List.of(),
                    List.of(new OutputDeclaration(firstPort, first, 3, OutputKind.PRIMARY),
                            new OutputDeclaration(secondPort, second, 3, OutputKind.SECONDARY)));
            CraftNode consumer = new CraftNode(CONSUMER, RECIPE, ModType.CUSTOM_GUI.id(),
                    null, 1, List.of(), List.of(), false, null, null,
                    List.of(new InputDemand(ironInput, IRON, 5, DemandRole.CONSUMED, tagged("first", 1)),
                            new InputDemand(goldInput, GOLD, 2, DemandRole.CONSUMED, gold(1))),
                    List.of(new OutputDeclaration(resultPort, diamond, 1, OutputKind.PRIMARY)));
            return new CraftPlanGraph(1, List.of(producer, consumer), List.of(
                    new MaterialAllocation(new AllocationId(0), ironInput,
                            new MaterialSource.ProducerOutput(firstPort), first, 2),
                    new MaterialAllocation(new AllocationId(1), ironInput,
                            new MaterialSource.ProducerOutput(secondPort), second, 3),
                    new MaterialAllocation(new AllocationId(2), goldInput, initialSource, goldKey, 2)),
                    List.of(new RootDemand(Ingredient.of(Items.DIAMOND), 1, 0,
                            new ItemStack(Items.DIAMOND), List.of(new RootAllocation(
                            new MaterialSource.ProducerOutput(resultPort), diamond, 1)))),
                    List.of(), List.of(PRODUCER, CONSUMER));
        }
    }

    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static ItemStack tagged(String variant, int count) {
        ItemStack stack = new ItemStack(Items.IRON_INGOT, count);
        stack.getOrCreateTag().putString("variant", variant);
        return stack;
    }

    private static ItemStack gold(int count) {
        return new ItemStack(Items.GOLD_INGOT, count);
    }

    private static void assertAmounts(List<ItemStack> expected, List<ItemStack> actual) {
        assertEquals(amounts(expected), amounts(actual));
    }

    private static Map<CraftingResolver.StackKey, Integer> amounts(List<ItemStack> stacks) {
        Map<CraftingResolver.StackKey, Integer> amounts = new LinkedHashMap<>();
        for (ItemStack stack : stacks) {
            if (!stack.isEmpty()) amounts.merge(CraftingResolver.StackKey.of(stack, true),
                    stack.getCount(), Integer::sum);
        }
        return amounts;
    }
}
