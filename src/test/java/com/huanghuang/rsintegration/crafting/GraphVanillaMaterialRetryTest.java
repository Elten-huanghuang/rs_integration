package com.huanghuang.rsintegration.crafting;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.crafting.endpoint.CraftStorageEndpoint;
import com.huanghuang.rsintegration.crafting.graph.AllocationId;
import com.huanghuang.rsintegration.crafting.graph.CraftNode;
import com.huanghuang.rsintegration.crafting.graph.CraftPlanGraph;
import com.huanghuang.rsintegration.crafting.graph.DagScheduler;
import com.huanghuang.rsintegration.crafting.graph.DemandRole;
import com.huanghuang.rsintegration.crafting.graph.InputDemand;
import com.huanghuang.rsintegration.crafting.graph.InputPortId;
import com.huanghuang.rsintegration.crafting.graph.MaterialAllocation;
import com.huanghuang.rsintegration.crafting.graph.MaterialKey;
import com.huanghuang.rsintegration.crafting.graph.MaterialSource;
import com.huanghuang.rsintegration.crafting.graph.NodeId;
import com.huanghuang.rsintegration.storage.StorageBackendId;
import com.huanghuang.rsintegration.storage.StorageDiagnosticCode;
import com.huanghuang.rsintegration.storage.StorageItemKey;
import com.huanghuang.rsintegration.storage.StorageOperationMode;
import com.huanghuang.rsintegration.storage.StorageOperationResult;
import com.huanghuang.rsintegration.storage.StorageOperationStatus;
import com.huanghuang.rsintegration.storage.StorageReference;
import com.huanghuang.rsintegration.storage.StorageSession;
import com.huanghuang.rsintegration.storage.StorageSnapshot;
import com.huanghuang.rsintegration.storage.StorageSnapshotResult;
import com.huanghuang.rsintegration.storage.StoredItem;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 通过真实节点派发与取料账本复现失败后的逐 tick 重试。 */
class GraphVanillaMaterialRetryTest extends BootstrapTest {
    private static final NodeId NODE = new NodeId(0);

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
    void invalidRequestStopsInsteadOfRetryingEveryTick() throws Exception {
        Fixture fixture = new Fixture(StorageOperationStatus.INVALID_REQUEST);
        for (int tick = 0; tick < 20; tick++) fixture.attempt(tick);

        assertEquals(DagScheduler.NodeState.FAILED, fixture.scheduler.state(NODE));
        assertEquals(1, fixture.extractions);
        assertEquals(2, fixture.stockCount());
        assertTrue(fixture.failureDetail().contains("INVALID_REQUEST"));
        assertTrue(fixture.failureDetail().contains("minecraft:iron_ingot"));
    }

    @Test
    void missingMaterialBacksOffThenUsesTheExistingRetryDeadline() throws Exception {
        Fixture fixture = new Fixture(StorageOperationStatus.NOT_FOUND);
        fixture.attempt(0);
        fixture.attempt(1);
        assertEquals(1, fixture.extractions);
        fixture.attempt(2);
        assertEquals(2, fixture.extractions);
        fixture.attempt(3);
        assertEquals(2, fixture.extractions);
        fixture.attempt(603);

        assertEquals(DagScheduler.NodeState.FAILED, fixture.scheduler.state(NODE));
        assertEquals(3, fixture.extractions);
        assertEquals(2, fixture.stockCount());
    }

    @Test
    void partialExtractionIsRefundedBeforeEachDelayedRetry() throws Exception {
        Fixture fixture = new Fixture(StorageOperationStatus.PARTIAL);
        fixture.attempt(0);
        assertEquals(2, fixture.stockCount());
        fixture.attempt(1);
        assertEquals(1, fixture.extractions);
        fixture.attempt(2);

        assertEquals(2, fixture.extractions);
        assertEquals(2, fixture.stockCount());
        assertEquals(DagScheduler.NodeState.READY, fixture.scheduler.state(NODE));
    }

    @Test
    void uncertainTransferStopsWithoutReplayingExtraction() throws Exception {
        Fixture fixture = new Fixture(StorageOperationStatus.INDETERMINATE);
        for (int tick = 0; tick < 20; tick++) fixture.attempt(tick);

        assertEquals(DagScheduler.NodeState.FAILED, fixture.scheduler.state(NODE));
        assertEquals(1, fixture.extractions);
        assertEquals(2, fixture.stockCount());
    }

    private static final class Fixture {
        final MinecraftServer server = mock(MinecraftServer.class);
        final ServerPlayer player = mock(ServerPlayer.class);
        final StorageBackendId backend = new StorageBackendId("test");
        final StorageSession session = mock(StorageSession.class, CALLS_REAL_METHODS);
        final CraftStorageEndpoint endpoint = () -> session;
        final List<ItemStack> stock = new ArrayList<>(List.of(new ItemStack(Items.IRON_INGOT, 2)));
        final AsyncCraftChain chain;
        final DagScheduler scheduler;
        int extractions;

        Fixture(StorageOperationStatus status) throws Exception {
            Field serverField = ServerPlayer.class.getField("server");
            serverField.setAccessible(true);
            serverField.set(player, server);
            when(player.getUUID()).thenReturn(UUID.randomUUID());
            when(player.getInventory()).thenReturn(new Inventory(player));
            when(session.reference()).thenReturn(new StorageReference(backend, "vanilla-retry"));
            when(session.snapshotItems(player)).thenAnswer(call -> StorageSnapshotResult.success(
                    new StorageSnapshot(backend, stock.stream().filter(stack -> !stack.isEmpty())
                            .map(stack -> new StoredItem(StorageItemKey.fromItemStack(backend, stack),
                                    stack.getCount())).toList())));
            when(session.extractExact(eq(player), any(), anyLong(), eq(false))).thenAnswer(call -> {
                extractions++;
                long requested = call.getArgument(2);
                if (status == StorageOperationStatus.PARTIAL) {
                    ItemStack fragment = stock.stream().filter(stack -> !stack.isEmpty())
                            .findFirst().orElseThrow().split(1);
                    return StorageOperationResult.extracted(StorageOperationMode.PERFORM, requested,
                            List.of(fragment));
                }
                if (status == StorageOperationStatus.NOT_FOUND) {
                    return StorageOperationResult.extracted(StorageOperationMode.PERFORM, requested, List.of());
                }
                if (status == StorageOperationStatus.INDETERMINATE) {
                    return StorageOperationResult.indeterminateExtraction(requested, List.of(), List.of(),
                            StorageDiagnosticCode.NATIVE_OPERATION_FAILED_AFTER_MUTATION);
                }
                return StorageOperationResult.failedExtraction(StorageOperationMode.PERFORM, requested,
                        status, List.of(), List.of());
            });
            when(session.extractMatching(eq(player), any(), anyLong(), eq(false))).thenAnswer(call ->
                    StorageOperationResult.extracted(StorageOperationMode.PERFORM, call.getArgument(2), List.of()));
            when(session.insert(eq(player), any(), eq(false))).thenAnswer(call -> {
                ItemStack stack = call.getArgument(1);
                stock.add(stack.copy());
                return StorageOperationResult.inserted(StorageOperationMode.PERFORM, stack, ItemStack.EMPTY);
            });
            MaterialKey material = MaterialKey.of(new ItemStack(Items.IRON_INGOT));
            InputPortId input = new InputPortId(NODE, 0);
            CraftNode node = new CraftNode(NODE, new ResourceLocation("test", "vanilla"),
                    ModType.GENERIC.id(), null, 1, List.of(), List.of(), false, null, null,
                    List.of(new InputDemand(input, Ingredient.of(Items.IRON_INGOT), 2,
                            DemandRole.CONSUMED, new ItemStack(Items.IRON_INGOT))), List.of());
            CraftPlanGraph graph = new CraftPlanGraph(1, List.of(node),
                    List.of(new MaterialAllocation(new AllocationId(0), input,
                            new MaterialSource.InitialPool(material), material, 2)),
                    List.of(), List.of(), List.of(NODE));
            chain = new AsyncCraftChain(player.getUUID(), server, endpoint, graph);
            scheduler = (DagScheduler) field(chain, "graphScheduler");
        }

        void attempt(int tick) throws Exception {
            when(server.getTickCount()).thenReturn(tick);
            Method settle = AsyncCraftChain.class.getDeclaredMethod("settleReadyVanillaNodes",
                    ServerPlayer.class, VanillaCraftingTickBudget.ChainAllowance.class);
            settle.setAccessible(true);
            settle.invoke(chain, player, new VanillaCraftingTickBudget(8).allowance(8));
        }

        int stockCount() {
            return stock.stream().mapToInt(ItemStack::getCount).sum();
        }

        String failureDetail() throws Exception {
            return String.valueOf(((Map<?, ?>) field(chain, "graphFailureDetails")).get(NODE));
        }
    }

    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }
}
