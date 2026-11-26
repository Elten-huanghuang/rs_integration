package com.huanghuang.rsintegration.storage;

import com.huanghuang.rsintegration.crafting.TaskOutputReception;
import com.huanghuang.rsintegration.crafting.endpoint.CraftStorageEndpoint;
import com.huanghuang.rsintegration.crafting.endpoint.CraftStorageEndpoints;
import com.huanghuang.rsintegration.storage.rs.RefinedStorageOutputReception;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import com.refinedmods.refinedstorage.api.network.INetwork;
import com.refinedmods.refinedstorage.api.network.security.ISecurityManager;
import com.refinedmods.refinedstorage.api.network.security.Permission;
import com.refinedmods.refinedstorage.api.util.Action;
import com.refinedmods.refinedstorage.apiimpl.storage.tracker.ItemStorageTracker;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.util.FakePlayer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StorageRestockSupportTest extends BootstrapTest {
    @Test
    void onlyConfirmedZeroStockEnablesFallback() {
        var endpoint = mock(CraftStorageEndpoint.class);
        var player = mock(ServerPlayer.class);
        var template = new ItemStack(Items.APPLE);
        var mode = StorageOperationMode.PERFORM;
        var key = StorageItemKey.fromItemStack(new StorageBackendId("test"), template);
        var session = mock(StorageSession.class);
        when(endpoint.session()).thenReturn(session);
        when(session.itemKey(any())).thenReturn(key);
        when(endpoint.extractExact(player, template, 1, false)).thenReturn(
                StorageOperationResult.extracted(mode, 1, List.of()),
                StorageOperationResult.failedExtraction(mode, 1, StorageOperationStatus.DENIED, List.of(), List.of()),
                StorageOperationResult.indeterminateExtraction(1, List.of(template), List.of(),
                        StorageDiagnosticCode.NATIVE_OPERATION_FAILED_AFTER_MUTATION));
        var missing = StorageRestockSupport.extractOutcome(endpoint, player, template, 1);
        assertTrue(missing.confirmedMissing());
        assertFalse(missing.failed());
        var denied = StorageRestockSupport.extractOutcome(endpoint, player, template, 1);
        assertFalse(denied.confirmedMissing());
        assertTrue(denied.failed());
        var uncertain = StorageRestockSupport.extractOutcome(endpoint, player, template, 1);
        assertFalse(uncertain.confirmedMissing());
        assertTrue(uncertain.failed());
        assertTrue(uncertain.stack().is(Items.APPLE));
    }

    @Test
    void exactFragmentsAreMergedWithoutChangingTheirIdentity() {
        ItemStack template = new ItemStack(Items.LAPIS_LAZULI);

        ItemStack merged = StorageRestockSupport.mergeExactStacks(template, List.of(
                new ItemStack(Items.LAPIS_LAZULI, 20),
                new ItemStack(Items.LAPIS_LAZULI, 12)),
                ItemStack::isSameItemSameTags);

        assertEquals(32, merged.getCount());
        assertTrue(ItemStack.isSameItemSameTags(template, merged));
    }

    @Test
    void unexpectedExtractionIdentityRejectsTheWholeResult() {
        ItemStack merged = StorageRestockSupport.mergeExactStacks(
                new ItemStack(Items.LAPIS_LAZULI), List.of(
                        new ItemStack(Items.LAPIS_LAZULI, 3),
                        new ItemStack(Items.DIAMOND, 1)),
                ItemStack::isSameItemSameTags);

        assertTrue(merged.isEmpty());
    }

    @Test
    void refundUpdatesLastModifiedWithoutFeedingWaitingCraft() throws Exception {
        MinecraftServer server = mock(MinecraftServer.class);
        when(server.isSameThread()).thenReturn(true);
        ServerPlayer player = mock(FakePlayer.class);
        var serverField = ServerPlayer.class.getField("server");
        serverField.setAccessible(true);
        serverField.set(player, server);
        when(player.getName()).thenReturn(Component.literal("refund-player"));

        var tracker = new ItemStorageTracker(() -> { });
        INetwork network = mock(INetwork.class, RETURNS_DEEP_STUBS);
        ServerLevel level = mock(ServerLevel.class);
        when(level.dimension()).thenReturn(Level.OVERWORLD);
        when(network.canRun()).thenReturn(true);
        when(network.getLevel()).thenReturn(level);
        when(network.getPosition()).thenReturn(new BlockPos(1, 64, 1));
        when(network.getItemStorageTracker()).thenReturn(tracker);
        ISecurityManager security = mock(ISecurityManager.class);
        when(security.hasPermission(Permission.INSERT, player)).thenReturn(true);
        when(network.getSecurityManager()).thenReturn(security);

        var stored = new AtomicInteger();
        when(network.insertItem(any(), anyInt(), any())).thenAnswer(call ->
                RefinedStorageOutputReception.insertItem(network, call.getArgument(0), call.getArgument(1),
                        call.getArgument(2), (stack, size) -> {
                            if (call.getArgument(2) == Action.PERFORM) stored.addAndGet(size);
                            return ItemStack.EMPTY;
                        }));

        var endpoint = CraftStorageEndpoints.fromResolvedNetwork(network);
        ItemStack iron = new ItemStack(Items.IRON_INGOT, 3);
        var resource = TaskOutputReception.resource(iron);
        try (var reception = TaskOutputReception.open()) {
            reception.bindNetwork(network, () -> true);
            reception.prepare(List.of(new TaskOutputReception.Demand("iron", resource, 5, 5))).activate();

            long beforeRefund = System.currentTimeMillis();
            assertTrue(StorageRestockSupport.refund(endpoint, player, List.of(iron), "test"));
            assertEquals(3, stored.get());
            assertEquals(0, reception.received(resource));
            var modification = tracker.get(iron.copyWithCount(1));
            assertNotNull(modification);
            assertTrue(modification.getTime() >= beforeRefund);
            assertEquals("refund-player", modification.getName());

            assertTrue(network.insertItem(iron, 3, Action.PERFORM).isEmpty());
            assertEquals(3, reception.received(resource));
            assertEquals(3, stored.get());
        }
    }
}
