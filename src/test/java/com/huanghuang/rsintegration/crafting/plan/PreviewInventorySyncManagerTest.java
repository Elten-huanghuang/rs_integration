package com.huanghuang.rsintegration.crafting.plan;

import com.huanghuang.rsintegration.crafting.CraftPlanningRevision;
import com.huanghuang.rsintegration.crafting.MaterialSources;
import com.huanghuang.rsintegration.crafting.CraftingResolver.StackKey;
import com.huanghuang.rsintegration.crafting.endpoint.CraftStorageEndpoint;
import com.huanghuang.rsintegration.crafting.endpoint.CraftStorageEndpoints;
import com.huanghuang.rsintegration.crafting.tree.IngredientKey;
import com.huanghuang.rsintegration.mods.ironsspellbooks.InkFluidSupport;
import com.huanghuang.rsintegration.mods.ironsspellbooks.InkFluidTestFixtures;
import com.huanghuang.rsintegration.storage.StorageItemChangeListener;
import com.huanghuang.rsintegration.storage.StorageItemKey;
import com.huanghuang.rsintegration.storage.StorageItemSubscription;
import com.huanghuang.rsintegration.storage.StoragePermission;
import com.huanghuang.rsintegration.storage.StorageSession;
import com.huanghuang.rsintegration.storage.StorageSnapshot;
import com.huanghuang.rsintegration.storage.StorageSnapshotResult;
import com.huanghuang.rsintegration.storage.StorageSnapshotStatus;
import com.huanghuang.rsintegration.storage.StoredItem;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.fluids.FluidStack;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.huanghuang.rsintegration.crafting.plan.PreviewInventoryModelTest.REFERENCE;
import static com.huanghuang.rsintegration.crafting.plan.PreviewInventoryModelTest.initial;
import static com.huanghuang.rsintegration.crafting.plan.PreviewInventoryModelTest.plan;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PreviewInventorySyncManagerTest extends BootstrapTest {
    @Test
    void fluidPipesUpdateTheTrackedFluidWithoutInvalidatingItemSnapshots() {
        ItemStack token = InkFluidSupport.token(InkFluidTestFixtures.tokenItem(), new FluidStack(Fluids.WATER, 1));
        var fixture = new Fixture(true, token, 1000, 1200);
        try (var local = mockStatic(MaterialSources.class)) {
            local.when(() -> MaterialSources.countInventoryForTypes(fixture.player, Set.of(token.getItem()))).thenReturn(Map.of());
            fixture.start();
            fixture.sent.clear();
            fixture.listener().onFluidChanged(new FluidStack(Fluids.WATER, 1), 900);
            fixture.state.tick(fixture.player, 2, 20, fixture.sent::add);
            assertEquals(100, fixture.sent.get(0).entries().get(0).missing());
            fixture.sent.clear();
            fixture.listener().onFluidChanged(new FluidStack(Fluids.WATER, 1), 50_000_000_000L);
            fixture.state.tick(fixture.player, 3, 20, fixture.sent::add);
            assertEquals(50_000_000_000L, fixture.sent.get(0).entries().get(0).available());
            verify(fixture.endpoint, times(1)).snapshot(any(), any());
            verify(fixture.subscription, never()).close();
        }
    }

    @Test
    void derivedContainersDoNotCertifySharedBucketAndFluidSupplies() {
        var fixture = new Fixture(true, new ItemStack(Items.WATER_BUCKET), 2, 1);
        when(fixture.storage.countDerivedContainer(eq(fixture.player), any())).thenReturn(1L);
        try (var local = mockStatic(MaterialSources.class)) {
            local.when(() -> MaterialSources.countInventoryForTypes(fixture.player, Set.of(Items.WATER_BUCKET))).thenReturn(Map.of());
            fixture.start();
            assertEquals(0, fixture.sent.get(0).entries().get(0).missing());
            assertEquals(PreviewInventoryModel.Status.REPLAN, fixture.sent.get(0).status());
        }
    }

    @Test
    void nativeChurnCoalescesAndDoesNotScanNetworkAgain() {
        var fixture = new Fixture(true);
        try (var local = mockStatic(MaterialSources.class)) {
            local.when(() -> MaterialSources.countInventoryForTypes(fixture.player, Set.of(Items.IRON_INGOT))).thenReturn(Map.of());
            fixture.start();
            assertEquals(PreviewInventoryModel.Status.READY, fixture.sent.get(0).status());
            fixture.sent.clear();
            for (int i = 0; i < 1_000; i++) fixture.listener().onChanged(new ItemStack(Items.IRON_INGOT), i);
            fixture.listener().onChanged(new ItemStack(Items.IRON_INGOT), 90);
            fixture.state.tick(fixture.player, 2, 20, fixture.sent::add);
            assertEquals(1, fixture.sent.size());
            assertEquals(10, fixture.sent.get(0).entries().get(0).missing());
            fixture.sent.clear();
            fixture.listener().onChanged(new ItemStack(Items.IRON_INGOT), 130);
            fixture.state.tick(fixture.player, 3, 20, fixture.sent::add);
            assertEquals(PreviewInventoryModel.Status.READY, fixture.sent.get(0).status());
            verify(fixture.endpoint, times(1)).snapshot(fixture.player, Set.of(Items.IRON_INGOT));
            verify(fixture.storage, never()).extractExact(any(), any(), anyLong(), anyBoolean());
        }
    }

    @Test
    void unrelatedItemAndFluidEventsSendNothingAndDoNotReadLocalInventory() {
        var fixture = new Fixture(true);
        try (var local = mockStatic(MaterialSources.class)) {
            local.when(() -> MaterialSources.countInventoryForTypes(fixture.player, Set.of(Items.IRON_INGOT))).thenReturn(Map.of());
            fixture.start();
            fixture.sent.clear();
            fixture.listener().onChanged(new ItemStack(Items.DIAMOND), 30);
            fixture.listener().onFluidChanged(new FluidStack(Fluids.WATER, 1), 1000);
            fixture.state.tick(fixture.player, 2, 20, fixture.sent::add);
            assertTrue(fixture.sent.isEmpty());
            local.verify(() -> MaterialSources.countInventoryForTypes(fixture.player, Set.of(Items.IRON_INGOT)), times(1));
            verify(fixture.endpoint, times(1)).snapshot(any(), any());
        }
    }

    @Test
    void movingStockFromNetworkToInventoryDoesNotLoseOrDuplicateIt() {
        var fixture = new Fixture(true);
        try (var local = mockStatic(MaterialSources.class)) {
            local.when(() -> MaterialSources.countInventoryForTypes(fixture.player, Set.of(Items.IRON_INGOT))).thenReturn(Map.of());
            fixture.start();
            fixture.sent.clear();
            local.when(() -> MaterialSources.countInventoryForTypes(fixture.player, Set.of(Items.IRON_INGOT))).thenReturn(Map.of(new StackKey(Items.IRON_INGOT, null), 30L));
            fixture.listener().onChanged(new ItemStack(Items.IRON_INGOT), 90);
            fixture.state.tick(fixture.player, 2, 20, fixture.sent::add);
            assertTrue(fixture.sent.isEmpty(), "合并后的数量仍为 120，不应该发送假缺料");
        }
    }

    @Test
    void cacheInvalidationDefersDetachUntilTickAndIgnoresOldCallbacks() {
        var fixture = new Fixture(true);
        try (var local = mockStatic(MaterialSources.class); var resolver = mockStatic(CraftStorageEndpoints.class)) {
            resolver.when(() -> CraftStorageEndpoints.resolve(REFERENCE, fixture.player)).thenReturn(Optional.of(fixture.endpoint));
            local.when(() -> MaterialSources.countInventoryForTypes(fixture.player, Set.of(Items.IRON_INGOT))).thenReturn(Map.of());
            fixture.start();
            StorageItemChangeListener old = fixture.listener();
            old.onInvalidated();
            verify(fixture.subscription, never()).close();
            fixture.state.tick(fixture.player, 2, 20, fixture.sent::add);
            verify(fixture.subscription).close();
            old.onChanged(new ItemStack(Items.IRON_INGOT), 0);
            assertTrue(fixture.state.pending.isEmpty());
            assertEquals(PreviewInventoryModel.Status.READY, fixture.state.model.status());
        }
    }

    @Test
    void fallbackPollsOnlyAtConfiguredIntervalAndFailedSnapshotIsUnknownNotEmpty() {
        var fixture = new Fixture(false);
        try (var local = mockStatic(MaterialSources.class); var resolver = mockStatic(CraftStorageEndpoints.class)) {
            resolver.when(() -> CraftStorageEndpoints.resolve(REFERENCE, fixture.player)).thenReturn(Optional.of(fixture.endpoint));
            local.when(() -> MaterialSources.countInventoryForTypes(fixture.player, Set.of(Items.IRON_INGOT))).thenReturn(Map.of());
            fixture.start();
            for (int tick = 2; tick < 20; tick++) fixture.state.tick(fixture.player, tick, 20, fixture.sent::add);
            verify(fixture.endpoint, times(1)).snapshot(any(), any());
            when(fixture.endpoint.snapshot(any(), any())).thenReturn(StorageSnapshotResult.failure(StorageSnapshotStatus.UNAVAILABLE));
            fixture.state.tick(fixture.player, 20, 20, fixture.sent::add);
            assertEquals(PreviewInventoryModel.Status.UNAVAILABLE, fixture.sent.get(fixture.sent.size() - 1).status());
            assertTrue(fixture.sent.get(fixture.sent.size() - 1).entries().isEmpty());
        }
    }

    @Test
    void revokedPermissionStopsUpdatesAndRecoverySendsNewBaseline() {
        var fixture = new Fixture(true);
        try (var local = mockStatic(MaterialSources.class); var resolver = mockStatic(CraftStorageEndpoints.class)) {
            resolver.when(() -> CraftStorageEndpoints.resolve(REFERENCE, fixture.player)).thenReturn(Optional.of(fixture.endpoint));
            local.when(() -> MaterialSources.countInventoryForTypes(fixture.player, Set.of(Items.IRON_INGOT))).thenReturn(Map.of());
            fixture.start();
            when(fixture.storage.hasPermission(fixture.player, StoragePermission.VIEW)).thenReturn(false);
            fixture.listener().onChanged(new ItemStack(Items.IRON_INGOT), 500);
            fixture.state.tick(fixture.player, 20, 20, fixture.sent::add);
            assertEquals(PreviewInventoryModel.Status.UNAVAILABLE, fixture.sent.get(fixture.sent.size() - 1).status());
            assertTrue(fixture.state.pending.isEmpty());
            when(fixture.storage.hasPermission(fixture.player, StoragePermission.VIEW)).thenReturn(true);
            fixture.state.tick(fixture.player, 40, 20, fixture.sent::add);
            assertTrue(fixture.sent.get(fixture.sent.size() - 1).full());
            assertEquals(120, fixture.sent.get(fixture.sent.size() - 1).entries().get(0).available());
        }
    }

    @Test
    void recipeReloadInvalidatesTheRouteWithoutStartingAnotherSearch() {
        var fixture = new Fixture(true);
        try (var local = mockStatic(MaterialSources.class)) {
            local.when(() -> MaterialSources.countInventoryForTypes(fixture.player, Set.of(Items.IRON_INGOT))).thenReturn(Map.of());
            fixture.start();
            CraftPlanningRevision.bump();
            fixture.state.tick(fixture.player, 2, 20, fixture.sent::add);
            assertEquals(PreviewInventoryModel.Status.UNAVAILABLE, fixture.sent.get(fixture.sent.size() - 1).status());
            verify(fixture.subscription).close();
        }
    }

    @Test
    void lateCloseCannotStopANewerWindowAndCloseIsIdempotent() {
        var fixture = new Fixture(true);
        fixture.state.watch(new PlanInventoryWatchPacket(10, 11, true), 0);
        fixture.state.watch(new PlanInventoryWatchPacket(10, 12, true), 0);
        fixture.state.watch(new PlanInventoryWatchPacket(10, 11, false), 0);
        assertEquals(12, fixture.state.watchId);
        try (var local = mockStatic(MaterialSources.class); var resolver = mockStatic(CraftStorageEndpoints.class)) {
            local.when(() -> MaterialSources.countInventoryForTypes(fixture.player, Set.of(Items.IRON_INGOT))).thenReturn(Map.of());
            resolver.when(() -> CraftStorageEndpoints.resolve(REFERENCE, fixture.player)).thenReturn(Optional.of(fixture.endpoint));
            fixture.start();
            var old = fixture.listener();
            fixture.state.watch(new PlanInventoryWatchPacket(10, 11, true), 2);
            fixture.state.watch(new PlanInventoryWatchPacket(9, 20, false), 2);
            assertEquals(12, fixture.state.watchId);
            verify(fixture.subscription, never()).close();
            fixture.state.close();
            fixture.state.close();
            old.onChanged(new ItemStack(Items.IRON_INGOT), 0);
            assertTrue(fixture.state.pending.isEmpty());
            verify(fixture.subscription).close();
        }
    }

    private static final class Fixture {
        final ServerPlayer player = mock(ServerPlayer.class);
        final CraftStorageEndpoint endpoint = mock(CraftStorageEndpoint.class);
        final StorageSession storage = mock(StorageSession.class);
        final StorageItemSubscription subscription = mock(StorageItemSubscription.class);
        final PreviewInventorySyncManager.Session state;
        final List<PlanInventoryUpdatePacket> sent = new ArrayList<>();

        Fixture(boolean subscribed) {
            this(subscribed, new ItemStack(Items.IRON_INGOT), 100, 120);
        }

        Fixture(boolean subscribed, ItemStack input, long required, long available) {
            when(endpoint.session()).thenReturn(storage);
            when(endpoint.isAvailable()).thenReturn(true);
            when(storage.reference()).thenReturn(REFERENCE);
            when(storage.hasPermission(player, StoragePermission.VIEW)).thenReturn(true);
            when(subscription.isValid()).thenReturn(true);
            when(storage.subscribeItemChanges(any())).thenReturn(subscribed ? Optional.of(subscription) : Optional.empty());
            when(endpoint.snapshot(player, Set.of(input.getItem()))).thenReturn(StorageSnapshotResult.success(
                    new StorageSnapshot(REFERENCE.backendId(), List.of(new StoredItem(StorageItemKey.fromItemStack(REFERENCE.backendId(), input), available)))));
            state = new PreviewInventorySyncManager.Session(plan(List.of(initial(input, required)), List.of(), Map.of()), 10, 0);
            state.watchId = 11;
        }

        void start() {
            state.bind(player, endpoint);
            state.tick(player, 1, 20, sent::add);
        }

        StorageItemChangeListener listener() {
            var capture = ArgumentCaptor.forClass(StorageItemChangeListener.class);
            verify(storage, atLeastOnce()).subscribeItemChanges(capture.capture());
            return capture.getValue();
        }
    }
}
