package com.huanghuang.rsintegration.crafting;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.crafting.endpoint.CraftStorageEndpoint;
import com.huanghuang.rsintegration.crafting.graph.MaterialKey;
import com.huanghuang.rsintegration.storage.StorageBackendId;
import com.huanghuang.rsintegration.storage.StorageDiagnosticCode;
import com.huanghuang.rsintegration.storage.StorageItemKey;
import com.huanghuang.rsintegration.storage.StorageOperationMode;
import com.huanghuang.rsintegration.storage.StorageOperationResult;
import com.huanghuang.rsintegration.storage.StorageReference;
import com.huanghuang.rsintegration.storage.StorageSession;
import com.huanghuang.rsintegration.storage.StorageSnapshot;
import com.huanghuang.rsintegration.storage.StorageSnapshotResult;
import com.huanghuang.rsintegration.storage.StoredItem;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import com.huanghuang.rsintegration.mods.ironsspellbooks.InkFluidSupport;
import com.huanghuang.rsintegration.mods.ironsspellbooks.InkFluidTestFixtures;
import com.huanghuang.rsintegration.util.PlayerUtils;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterEach;
import org.mockito.MockedStatic;
import net.minecraftforge.fml.ModList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RuntimeMaterialAccountTest extends BootstrapTest {
    private static final StorageBackendId BACKEND = new StorageBackendId("test");
    private static final MaterialKey IRON = MaterialKey.of(new ItemStack(Items.IRON_INGOT));
    private static final MaterialKey GOLD = MaterialKey.of(new ItemStack(Items.GOLD_INGOT));
    private final Map<MaterialKey, Long> stock = new LinkedHashMap<>();
    private final AtomicInteger extractions = new AtomicInteger();
    private MinecraftServer server;
    private ServerPlayer player;
    private StorageSession session;
    private CraftStorageEndpoint endpoint;
    private MockedStatic<ModList> mods;

    @AfterEach void cleanup() { mods.close(); }

    @BeforeAll
    static void configure() {
        CommentedConfig config = CommentedConfig.inMemory();
        RSIntegrationConfig.SERVER_SPEC.correct(config);
        RSIntegrationConfig.SERVER_SPEC.setConfig(config);
        FMLJavaModLoadingContext context = mock(FMLJavaModLoadingContext.class);
        when(context.getModEventBus()).thenReturn(mock(IEventBus.class));
        try (var loading = mockStatic(FMLJavaModLoadingContext.class)) {
            loading.when(FMLJavaModLoadingContext::get).thenReturn(context);
            assertNotNull(RSIntegrationMod.LOGGER);
        }
    }

    @BeforeEach
    void prepare() throws Exception {
        mods = mockStatic(ModList.class);
        mods.when(ModList::get).thenReturn(mock(ModList.class));
        server = mock(MinecraftServer.class);
        PlayerList players = mock(PlayerList.class);
        when(server.getPlayerList()).thenReturn(players);
        player = mock(ServerPlayer.class);
        when(player.getUUID()).thenReturn(UUID.randomUUID());
        when(players.getPlayer(player.getUUID())).thenReturn(player);
        when(player.getInventory()).thenReturn(new Inventory(player));
        var serverField = ServerPlayer.class.getField("server");
        serverField.setAccessible(true);
        serverField.set(player, server);
        session = mock(StorageSession.class, CALLS_REAL_METHODS);
        when(session.reference()).thenReturn(new StorageReference(BACKEND, "shared-network"));
        when(session.snapshotItems(any())).thenAnswer(call -> StorageSnapshotResult.success(
                new StorageSnapshot(BACKEND, stock.entrySet().stream()
                        .filter(entry -> entry.getValue() > 0)
                        .map(entry -> new StoredItem(StorageItemKey.fromItemStack(BACKEND,
                                entry.getKey().toStack(1)), entry.getValue())).toList())));
        when(session.extractExact(any(), any(), anyLong(), eq(false))).thenAnswer(call -> {
            extractions.incrementAndGet();
            StorageItemKey key = call.getArgument(1);
            long requested = call.getArgument(2);
            MaterialKey material = stock.keySet().stream().filter(candidate ->
                    StorageItemKey.fromItemStack(BACKEND, candidate.toStack(1)).equals(key)).findFirst().orElseThrow();
            int taken = (int) Math.min(requested, stock.get(material));
            stock.put(material, stock.get(material) - taken);
            return StorageOperationResult.extracted(StorageOperationMode.PERFORM, requested,
                    taken == 0 ? List.of() : List.of(material.toStack(taken)));
        });
        when(session.insert(any(), any(), eq(false))).thenAnswer(call -> {
            ItemStack stack = call.getArgument(1);
            stock.merge(MaterialKey.of(stack), (long) stack.getCount(), Long::sum);
            return StorageOperationResult.inserted(StorageOperationMode.PERFORM, stack, ItemStack.EMPTY);
        });
        endpoint = () -> session;
    }

    private RuntimeMaterialAccount account() {
        return new RuntimeMaterialAccount(UUID.randomUUID(), player.getUUID(), server, endpoint);
    }

    private boolean acquire(RuntimeMaterialAccount account, MaterialKey material, long count) {
        return account.ensure(Map.of(material, count), List.of(), player);
    }

    @Test
    void mixedItemAndFluidPartialAcquisitionRollsBackBothAndDoesNotHoldHalfAdmission() {
        MaterialKey water = MaterialKey.of(InkFluidSupport.token(InkFluidTestFixtures.tokenItem(),
                new FluidStack(Fluids.WATER, 1)));
        stock.put(IRON, 2L);
        stock.put(water, 1000L);
        doAnswer(call -> {
            stock.put(water, 600L);
            return StorageOperationResult.extracted(StorageOperationMode.PERFORM, 1000,
                    List.of(water.toStack(400)));
        }).when(session).extractExact(eq(player), eq(StorageItemKey.fromItemStack(BACKEND, water.toStack(1))),
                eq(1000L), eq(false));
        Map<MaterialKey, Long> required = new LinkedHashMap<>();
        required.put(IRON, 2L);
        required.put(water, 1000L);
        RuntimeMaterialAccount account = account();
        assertFalse(account.ensure(required, List.of(), player));
        assertEquals(2L, stock.get(IRON));
        assertEquals(1000L, stock.get(water));
        assertEquals(0, account.count(IRON));
        assertEquals(0, account.count(water));
        assertFalse(account.uncertain());
    }

    @Test
    void sameNetworkAllowsBothFundedTasksButNeverPromisesOneItemTwice() {
        stock.put(IRON, 10L);
        RuntimeMaterialAccount first = account();
        RuntimeMaterialAccount second = account();
        assertTrue(acquire(first, IRON, 6));
        assertFalse(acquire(second, IRON, 5));
        assertTrue(acquire(second, IRON, 4));
        assertEquals(0L, stock.get(IRON));
        assertEquals(6L, first.count(IRON));
        assertEquals(4L, second.count(IRON));
        assertTrue(acquire(first, IRON, 6));
        assertEquals(2, extractions.get());
    }

    @Test
    void differentMaterialsAreAdmittedIndependently() {
        stock.put(IRON, 1L);
        stock.put(GOLD, 1L);
        assertTrue(acquire(account(), IRON, 1));
        assertTrue(acquire(account(), GOLD, 1));
        assertEquals(2, extractions.get());
    }

    @Test
    void admissionShortageDoesNotHoldEarlierMaterials() {
        stock.put(IRON, 1L);
        stock.put(GOLD, 0L);
        Map<MaterialKey, Long> required = new LinkedHashMap<>();
        required.put(IRON, 1L);
        required.put(GOLD, 1L);
        RuntimeMaterialAccount account = account();
        assertFalse(account.ensure(required, List.of(), player));
        assertEquals(1L, stock.get(IRON));
        assertEquals(0L, account.count(IRON));
        assertEquals(0, extractions.get());
    }

    @Test
    void nodeReservationsAreExclusiveAndNeverExtractPublicStockAgain() {
        stock.put(IRON, 2L);
        RuntimeMaterialAccount account = account();
        assertTrue(acquire(account, IRON, 2));
        try (var ignored = RuntimeMaterialAccount.enter(account)) {
            ExtractionLedger first = new ExtractionLedger();
            ExtractionLedger second = new ExtractionLedger();
            assertEquals(2, first.reserveFromEndpoint(Ingredient.of(Items.IRON_INGOT), 2, endpoint, player).getCount());
            assertTrue(second.reserveFromEndpoint(Ingredient.of(Items.IRON_INGOT), 1, endpoint, player).isEmpty());
            assertTrue(first.commitToEndpoint(endpoint, player));
            first.refundCommittedToEndpoint(endpoint, player);
            first.refundCommittedToEndpoint(endpoint, player);
            assertEquals(2L, account.count(IRON));
            assertEquals(0L, stock.get(IRON));
        }
        account.close();
        account.close();
        assertEquals(2L, stock.get(IRON));
        assertEquals(1, extractions.get());
    }

    @Test
    void cancellingUncommittedNodeReturnsItsClaimToTask() {
        stock.put(IRON, 2L);
        RuntimeMaterialAccount account = account();
        assertTrue(acquire(account, IRON, 2));
        try (var ignored = RuntimeMaterialAccount.enter(account)) {
            ExtractionLedger node = new ExtractionLedger();
            assertEquals(1, node.reserveFromInventory(Ingredient.of(Items.IRON_INGOT), 1, player).getCount());
            node.cancelLastReservation();
            assertEquals(2L, account.count(IRON));
            node.reserveFromInventory(Ingredient.of(Items.IRON_INGOT), 1, player);
            node.close();
            assertEquals(2L, account.count(IRON));
            node.reset();
            assertEquals(2L, account.count(IRON));
        }
    }

    @Test
    void completingOneNodeOnlyRefundsUnusedTaskStock() {
        stock.put(IRON, 5L);
        RuntimeMaterialAccount account = account();
        assertTrue(acquire(account, IRON, 5));
        try (var ignored = RuntimeMaterialAccount.enter(account)) {
            ExtractionLedger node = new ExtractionLedger();
            node.reserveExactAcrossNetworkAndInventory(IRON.toStack(1), 3, endpoint, player);
            assertTrue(node.commitToEndpoint(endpoint, player));
            node.settleAllCommitted();
            node.reset();
        }
        account.close();
        assertEquals(2L, stock.get(IRON));
    }

    @Test
    void supplementalAcquisitionUsesSameTaskAccount() {
        stock.put(IRON, 2L);
        RuntimeMaterialAccount account = account();
        assertTrue(acquire(account, IRON, 1));
        try (var ignored = RuntimeMaterialAccount.enter(account)) {
            ExtractionLedger node = new ExtractionLedger();
            node.reserveExactAcrossNetworkAndInventory(IRON.toStack(1), 2, endpoint, player);
            assertTrue(node.commitToEndpoint(endpoint, player));
            node.refundCommittedToEndpoint(endpoint, player);
        }
        account.close();
        assertEquals(2L, stock.get(IRON));
        assertEquals(2, extractions.get());
    }

    @Test
    void exactNbtIsNeverReplacedWithAnotherVariant() {
        ItemStack named = new ItemStack(Items.IRON_INGOT);
        CompoundTag tag = new CompoundTag();
        tag.putString("variant", "specific");
        named.setTag(tag);
        MaterialKey specific = MaterialKey.of(named);
        stock.put(IRON, 1L);
        stock.put(specific, 1L);
        RuntimeMaterialAccount account = account();
        assertTrue(acquire(account, specific, 1));
        assertEquals(1L, stock.get(IRON));
        assertEquals(0L, stock.get(specific));
        assertEquals(0L, account.count(IRON));
    }

    @Test
    void indeterminateExtractionIsNeverRetried() {
        stock.put(IRON, 2L);
        doAnswer(call -> {
            extractions.incrementAndGet();
            stock.put(IRON, stock.get(IRON) - 1);
            return StorageOperationResult.indeterminateExtraction(1, List.of(), List.of(),
                    StorageDiagnosticCode.BACKEND_EXCEPTION);
        }).when(session).extractExact(any(), any(), anyLong(), eq(false));
        RuntimeMaterialAccount account = account();
        assertFalse(acquire(account, IRON, 1));
        assertTrue(account.uncertain());
        assertFalse(acquire(account, IRON, 1));
        account.close();
        assertEquals(1, extractions.get());
        assertEquals(1L, stock.get(IRON));
    }

    @Test
    void scopeRestoresPreviousTaskAfterNestedFailure() {
        RuntimeMaterialAccount first = account();
        RuntimeMaterialAccount second = account();
        try (var ignored = RuntimeMaterialAccount.enter(first)) {
            assertThrows(IllegalStateException.class, () -> {
                try (var nested = RuntimeMaterialAccount.enter(second)) {
                    assertSame(second, RuntimeMaterialAccount.current());
                    throw new IllegalStateException();
                }
            });
            assertSame(first, RuntimeMaterialAccount.current());
        }
        assertNull(RuntimeMaterialAccount.current());
    }

    @Test
    void secondPlayerCannotAcquireItemsHeldByFirstPlayer() throws Exception {
        stock.put(IRON, 1L);
        RuntimeMaterialAccount first = account();
        assertTrue(acquire(first, IRON, 1));
        ServerPlayer other = mock(ServerPlayer.class);
        when(other.getUUID()).thenReturn(UUID.randomUUID());
        when(other.getInventory()).thenReturn(new Inventory(other));
        var serverField = ServerPlayer.class.getField("server");
        serverField.setAccessible(true);
        serverField.set(other, server);
        // 同一个存储会话可以由不同端点包装，不能因此重复分配。
        RuntimeMaterialAccount second = new RuntimeMaterialAccount(UUID.randomUUID(),
                other.getUUID(), server, () -> session);
        assertFalse(second.ensure(Map.of(IRON, 1L), List.of(), other));
        assertEquals(1, extractions.get());
        first.close();
        assertTrue(second.ensure(Map.of(IRON, 1L), List.of(), other));
    }

    @Test
    void partialExtractionRestoresEarlierConfirmedInputsBeforeRetry() {
        stock.put(IRON, 2L);
        stock.put(GOLD, 2L);
        doAnswer(call -> {
            stock.put(GOLD, 1L);
            return StorageOperationResult.extracted(StorageOperationMode.PERFORM, 2,
                    List.of(GOLD.toStack(1)));
        }).when(session).extractExact(any(), eq(StorageItemKey.fromItemStack(BACKEND, GOLD.toStack(1))),
                eq(2L), eq(false));
        Map<MaterialKey, Long> required = new LinkedHashMap<>();
        required.put(IRON, 2L);
        required.put(GOLD, 2L);
        RuntimeMaterialAccount account = account();
        assertFalse(account.ensure(required, List.of(), player));
        assertFalse(account.uncertain());
        assertEquals(2L, stock.get(IRON));
        assertEquals(2L, stock.get(GOLD));
        assertEquals(0L, account.count(IRON));
    }

    @Test
    void unknownRollbackFreezesTheAcquisition() {
        stock.put(IRON, 2L);
        doAnswer(call -> StorageOperationResult.extracted(StorageOperationMode.PERFORM, 2,
                List.of(IRON.toStack(1))))
                .when(session).extractExact(any(), any(), anyLong(), eq(false));
        doAnswer(call -> StorageOperationResult.indeterminateInsert(call.getArgument(1),
                StorageDiagnosticCode.BACKEND_EXCEPTION))
                .when(session).insert(any(), any(), eq(false));
        RuntimeMaterialAccount account = account();
        assertFalse(acquire(account, IRON, 2));
        assertTrue(account.uncertain());
        clearInvocations(session);
        assertFalse(acquire(account, IRON, 2));
        verify(session, never()).extractExact(any(), any(), anyLong(), anyBoolean());
    }

    @Test
    void inventoryOnlyTasksShareTheSamePhysicalInventory() {
        player.getInventory().items.set(0, IRON.toStack(1));
        RuntimeMaterialAccount first = new RuntimeMaterialAccount(UUID.randomUUID(),
                player.getUUID(), server, null);
        RuntimeMaterialAccount second = new RuntimeMaterialAccount(UUID.randomUUID(),
                player.getUUID(), server, null);
        try (var players = mockStatic(PlayerUtils.class)) {
            assertTrue(acquire(first, IRON, 1));
            assertFalse(acquire(second, IRON, 1));
            assertTrue(player.getInventory().items.get(0).isEmpty());
        }
    }

    @Test
    void directDelegateExtractionAlsoConsumesHeldInputs() {
        stock.put(IRON, 3L);
        RuntimeMaterialAccount account = account();
        assertTrue(acquire(account, IRON, 3));
        try (var ignored = RuntimeMaterialAccount.enter(account)) {
            assertEquals(2L, ExtractionLedger.extractTaskMaterial(player, endpoint,
                    Ingredient.of(Items.IRON_INGOT), IRON.toStack(1), 2, true)
                    .transferredAmount().orElseThrow());
            assertEquals(3L, account.count(IRON));
            assertEquals(2L, ExtractionLedger.extractTaskMaterial(player, endpoint,
                    Ingredient.of(Items.IRON_INGOT), IRON.toStack(1), 2, false)
                    .transferredAmount().orElseThrow());
        }
        account.close();
        assertEquals(1L, stock.get(IRON));
        assertEquals(1, extractions.get());
    }

    @Test
    void endpointSnapshotIncludesOnlyCurrentTasksHeldMaterialsOnce() {
        stock.put(IRON, 5L);
        stock.put(GOLD, 2L);
        RuntimeMaterialAccount first = account();
        RuntimeMaterialAccount second = account();
        assertTrue(acquire(first, IRON, 2));
        assertTrue(acquire(second, IRON, 1));
        try (var ignored = RuntimeMaterialAccount.enter(first)) {
            StorageItemKey ironKey = session.itemKey(IRON.toStack(1));
            assertEquals(4L, endpoint.snapshot(player).snapshot().orElseThrow().countExact(ironKey));
            assertEquals(4L, endpoint.snapshot(player, Set.of(Items.IRON_INGOT))
                    .snapshot().orElseThrow().countExact(ironKey));
            assertEquals(4L, MaterialSources.listAllAvailableFresh(player, endpoint)
                    .get(CraftingResolver.StackKey.of(IRON.toStack(1), true)));
            CraftStorageEndpoint sameNetwork = () -> session;
            assertEquals(4L, sameNetwork.snapshot(player).snapshot().orElseThrow().countExact(ironKey));
        }
        assertEquals(2L, endpoint.snapshot(player).snapshot().orElseThrow()
                .countExact(session.itemKey(IRON.toStack(1))));
    }

    @Test
    void endpointExactAndMatchingExtractionUseAccountAndPreserveSimulation() {
        stock.put(IRON, 3L);
        RuntimeMaterialAccount account = account();
        assertTrue(acquire(account, IRON, 3));
        try (var ignored = RuntimeMaterialAccount.enter(account)) {
            assertEquals(2L, endpoint.extractExact(player, IRON.toStack(1), 2, true)
                    .transferredAmount().orElseThrow());
            assertEquals(3L, account.count(IRON));
            assertEquals(2L, endpoint.extractExact(player, IRON.toStack(1), 2, false)
                    .transferredAmount().orElseThrow());
            assertEquals(1L, endpoint.extractMatching(player, Ingredient.of(Items.IRON_INGOT), 2, false)
                    .transferredAmount().orElseThrow());
            assertEquals(0L, account.count(IRON));
        }
        assertEquals(1, extractions.get());
    }

    @Test
    void otherNetworkDoesNotSeeOrConsumeCurrentTasksMaterials() {
        stock.put(IRON, 2L);
        RuntimeMaterialAccount account = account();
        assertTrue(acquire(account, IRON, 2));
        StorageSession otherSession = mock(StorageSession.class, CALLS_REAL_METHODS);
        when(otherSession.reference()).thenReturn(new StorageReference(BACKEND, "other-network"));
        when(otherSession.snapshotItems(player)).thenReturn(
                StorageSnapshotResult.success(new StorageSnapshot(BACKEND, List.of())));
        StorageOperationResult empty = StorageOperationResult.extracted(StorageOperationMode.PERFORM, 1, List.of());
        when(otherSession.extractExact(eq(player), any(), eq(1L), eq(false))).thenReturn(empty);
        CraftStorageEndpoint other = () -> otherSession;
        try (var ignored = RuntimeMaterialAccount.enter(account)) {
            assertTrue(other.snapshot(player).snapshot().orElseThrow().items().isEmpty());
            assertTrue(MaterialSources.listAllAvailableFresh(player, other).isEmpty());
            assertSame(empty, other.extractExact(player, IRON.toStack(1), 1, false));
            assertEquals(2L, account.count(IRON));
        }
    }

    @Test
    void refundLinkageFailureDoesNotStopReturningOtherMaterialsOrRetry() {
        stock.put(IRON, 1L);
        stock.put(GOLD, 1L);
        RuntimeMaterialAccount account = account();
        assertTrue(acquire(account, IRON, 1));
        assertTrue(acquire(account, GOLD, 1));
        doThrow(new NoClassDefFoundError("backend disappeared"))
                .when(session).insert(eq(player), argThat(stack -> stack.is(Items.IRON_INGOT)), eq(false));
        account.close();
        assertTrue(account.uncertain());
        assertEquals(1L, stock.get(GOLD));
        clearInvocations(session);
        account.close();
        verify(session, never()).insert(any(), any(), anyBoolean());
    }

    @Test
    void coordinatorRejectsDuplicateCraftAndReleasesUnusedStock() {
        stock.put(IRON, 1L);
        GlobalMaterialCoordinator coordinator = new GlobalMaterialCoordinator();
        UUID id = UUID.randomUUID();
        RuntimeMaterialAccount account = coordinator.open(id, player.getUUID(), server, endpoint);
        assertTrue(acquire(account, IRON, 1));
        assertThrows(IllegalStateException.class, () -> coordinator.open(id, player.getUUID(), server, endpoint));
        assertEquals(1, coordinator.size());
        coordinator.release(id);
        coordinator.release(id);
        assertEquals(0, coordinator.size());
        assertEquals(1L, stock.get(IRON));
    }
}
