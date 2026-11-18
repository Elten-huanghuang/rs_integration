package com.huanghuang.rsintegration.crafting.recovery;

import com.huanghuang.rsintegration.mods.ironsspellbooks.InkFluidSupport;
import com.huanghuang.rsintegration.mods.ironsspellbooks.InkFluidTestFixtures;
import com.huanghuang.rsintegration.crafting.MaterialSources;
import com.huanghuang.rsintegration.crafting.MaterialTransferAuditLedger;
import com.huanghuang.rsintegration.crafting.NetworkOutputReception;
import com.huanghuang.rsintegration.crafting.TaskOutputReception;
import com.huanghuang.rsintegration.crafting.endpoint.CraftStorageEndpoint;
import com.huanghuang.rsintegration.storage.StorageOperationMode;
import com.huanghuang.rsintegration.storage.StorageDiagnosticCode;
import com.huanghuang.rsintegration.storage.StorageOperationResult;
import com.huanghuang.rsintegration.storage.StoragePermission;
import com.huanghuang.rsintegration.storage.StorageSession;
import com.huanghuang.rsintegration.network.RSIntegrationNetwork;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import com.refinedmods.refinedstorage.api.network.INetwork;
import com.refinedmods.refinedstorage.api.network.security.ISecurityManager;
import com.refinedmods.refinedstorage.api.network.security.Permission;
import com.refinedmods.refinedstorage.api.util.Action;
import com.refinedmods.refinedstorage.api.storage.tracker.IStorageTracker;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.fluids.FluidStack;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doAnswer;

class FluidRefundItemTest extends BootstrapTest {
    @Test
    void oneItemStoresFullFluidAmountInNbt() {
        ItemStack voucher = FluidRefundItem.create(InkFluidTestFixtures.refundItem(), raw(1000),
                "v1|minecraft:overworld@1,2,3");
        assertEquals(1, voucher.getCount());
        assertEquals(1000, voucher.getTag().getCompound("Fluid").getInt("Amount"));
        assertEquals("v1|minecraft:overworld@1,2,3", voucher.getTag().getString("Network"));
    }

    @Test
    void partialInsertChangesOnlyNbtAndFullDiskKeepsItem() {
        ItemStack voucher = FluidRefundItem.create(InkFluidTestFixtures.refundItem(), raw(1000), null);
        ServerPlayer player = mock(ServerPlayer.class);
        when(player.getInventory()).thenReturn(mock(Inventory.class));
        INetwork network = mock(INetwork.class);
        when(network.canRun()).thenReturn(true);
        when(network.insertFluid(any(), eq(1000), eq(Action.PERFORM)))
                .thenReturn(new FluidStack(Fluids.WATER, 400));
        when(network.insertFluid(any(), eq(400), eq(Action.PERFORM)))
                .thenReturn(new FluidStack(Fluids.WATER, 400));
        try (MockedStatic<MaterialSources> materials = mockStatic(MaterialSources.class)) {
            assertEquals(FluidVoucherRecovery.RestoreResult.PARTIAL,
                    FluidVoucherRecovery.restore(player, voucher, network));
            assertEquals(1, voucher.getCount());
            assertEquals(400, voucher.getTag().getCompound("Fluid").getInt("Amount"));
            assertEquals(FluidVoucherRecovery.RestoreResult.NO_SPACE,
                    FluidVoucherRecovery.restore(player, voucher, network));
            assertEquals(400, voucher.getTag().getCompound("Fluid").getInt("Amount"));
            when(network.insertFluid(any(), eq(400), eq(Action.PERFORM)))
                    .thenReturn(FluidStack.EMPTY);
            assertEquals(FluidVoucherRecovery.RestoreResult.STORED,
                    FluidVoucherRecovery.restore(player, voucher, network));
        }
        assertTrue(voucher.isEmpty());
    }

    @Test
    void playerCanChooseAnotherNetworkWhenItAcceptsTheFluid() {
        ItemStack voucher = FluidRefundItem.create(InkFluidTestFixtures.refundItem(), raw(250),
                "v1|minecraft:overworld@9,9,9");
        ServerPlayer player = mock(ServerPlayer.class);
        when(player.getInventory()).thenReturn(mock(Inventory.class));
        INetwork network = mock(INetwork.class);
        when(network.canRun()).thenReturn(true);
        when(network.insertFluid(any(), eq(250), eq(Action.PERFORM))).thenReturn(FluidStack.EMPTY);
        try (MockedStatic<MaterialSources> materials = mockStatic(MaterialSources.class)) {
            assertEquals(FluidVoucherRecovery.RestoreResult.STORED,
                    FluidVoucherRecovery.restore(player, voucher, network));
        }
        assertTrue(voucher.isEmpty());
    }

    @Test
    void networkPermissionIsCheckedBeforeInserting() {
        ItemStack voucher = FluidRefundItem.create(InkFluidTestFixtures.refundItem(), raw(250), null);
        ServerPlayer player = mock(ServerPlayer.class);
        INetwork network = mock(INetwork.class);
        ISecurityManager security = mock(ISecurityManager.class);
        when(network.canRun()).thenReturn(true);
        when(network.getSecurityManager()).thenReturn(security);
        when(security.hasPermission(Permission.INSERT, player)).thenReturn(false);
        assertEquals(FluidVoucherRecovery.RestoreResult.NO_PERMISSION,
                FluidVoucherRecovery.restore(player, voucher, network));
        verify(network, never()).insertFluid(any(), anyInt(), any());
        assertEquals(250, voucher.getTag().getCompound("Fluid").getInt("Amount"));
    }

    @Test
    void bdPartialRefundPreservesFluidNbtAndOnlyConsumesTheAcceptedAmount() {
        FluidStack fluid = new FluidStack(Fluids.WATER, 1000);
        fluid.getOrCreateTag().putString("variant", "original");
        ItemStack voucher = FluidRefundItem.create(InkFluidTestFixtures.refundItem(),
                fluid.writeToNBT(new CompoundTag()), null);
        ServerPlayer player = mock(ServerPlayer.class);
        when(player.getInventory()).thenReturn(mock(Inventory.class));
        StorageSession session = mock(StorageSession.class);
        when(session.hasPermission(player, StoragePermission.INSERT)).thenReturn(true);
        CraftStorageEndpoint endpoint = () -> session;
        when(session.insert(eq(player), any(ItemStack.class), eq(false))).thenAnswer(call -> {
            ItemStack token = call.getArgument(1);
            assertTrue(InkFluidSupport.fluid(token).isFluidEqual(fluid));
            return StorageOperationResult.inserted(StorageOperationMode.PERFORM, token,
                    token.copyWithCount(400));
        });
        try (MockedStatic<FluidTokenItem> tokens = mockStatic(FluidTokenItem.class, CALLS_REAL_METHODS);
             MockedStatic<MaterialSources> materials = mockStatic(MaterialSources.class)) {
            tokens.when(() -> FluidTokenItem.token(any(FluidStack.class))).thenAnswer(call ->
                    FluidTokenItem.token(InkFluidTestFixtures.tokenItem(), call.getArgument(0)));
            assertEquals(FluidVoucherRecovery.RestoreResult.PARTIAL,
                    FluidVoucherRecovery.restoreEndpoint(player, voucher, endpoint));
            assertEquals(400, voucher.getTag().getCompound("Fluid").getInt("Amount"));
            assertEquals("original", voucher.getTag().getCompound("Fluid").getCompound("Tag").getString("variant"));
            doAnswer(call -> StorageOperationResult.inserted(StorageOperationMode.PERFORM,
                    call.getArgument(1), ItemStack.EMPTY)).when(session).insert(eq(player), any(ItemStack.class), eq(false));
            assertEquals(FluidVoucherRecovery.RestoreResult.STORED,
                    FluidVoucherRecovery.restoreEndpoint(player, voucher, endpoint));
            assertTrue(voucher.isEmpty());
        }
    }

    @Test
    void bdDeniedRefundLeavesTheVoucherUntouched() {
        ItemStack voucher = FluidRefundItem.create(InkFluidTestFixtures.refundItem(), raw(250), null);
        ServerPlayer player = mock(ServerPlayer.class);
        StorageSession session = mock(StorageSession.class);
        assertEquals(FluidVoucherRecovery.RestoreResult.NO_PERMISSION,
                FluidVoucherRecovery.restoreEndpoint(player, voucher, () -> session));
        verify(session, never()).insert(eq(player), any(), eq(false));
        assertEquals(250, voucher.getTag().getCompound("Fluid").getInt("Amount"));
    }

    @Test
    void ordinaryRightClickOnRsBlockRestoresTheHeldItem() {
        ItemStack voucher = FluidRefundItem.create(InkFluidTestFixtures.refundItem(), raw(250), null);
        ServerPlayer player = mock(ServerPlayer.class);
        Level level = mock(Level.class);
        PlayerInteractEvent.RightClickBlock event = mock(PlayerInteractEvent.RightClickBlock.class);
        INetwork network = mock(INetwork.class);
        when(player.level()).thenReturn(level);
        when(player.getInventory()).thenReturn(mock(Inventory.class));
        when(player.getItemInHand(InteractionHand.MAIN_HAND)).thenReturn(voucher);
        when(level.dimension()).thenReturn(Level.OVERWORLD);
        when(event.getEntity()).thenReturn(player);
        when(event.getHand()).thenReturn(InteractionHand.MAIN_HAND);
        when(event.getPos()).thenReturn(BlockPos.ZERO);
        when(network.canRun()).thenReturn(true);
        when(network.insertFluid(any(), eq(250), eq(Action.PERFORM))).thenReturn(FluidStack.EMPTY);
        try (MockedStatic<RSIntegrationNetwork> networks = mockStatic(RSIntegrationNetwork.class);
             MockedStatic<MaterialSources> materials = mockStatic(MaterialSources.class)) {
            networks.when(() -> RSIntegrationNetwork.resolveNetworkStrict(
                    isNull(), eq(Level.OVERWORLD), eq(BlockPos.ZERO))).thenReturn(network);
            FluidRefundInteraction.onRightClickBlock(event);
        }
        verify(event).setCanceled(true);
        verify(event).setCancellationResult(InteractionResult.SUCCESS);
        assertTrue(voucher.isEmpty());
    }

    private static CompoundTag raw(int amount) {
        return new FluidStack(Fluids.WATER, amount).writeToNBT(new CompoundTag());
    }

    @Test
    void ambiguousInsertLocksTheSavedVoucherAndRecordsOneAttemptAcrossRetries() {
        ItemStack voucher = FluidRefundItem.create(InkFluidTestFixtures.refundItem(), raw(250), null);
        ServerPlayer player = mock(ServerPlayer.class);
        UUID owner = UUID.randomUUID();
        when(player.getUUID()).thenReturn(owner);
        when(player.getInventory()).thenReturn(mock(Inventory.class));
        INetwork network = mock(INetwork.class);
        when(network.canRun()).thenReturn(true);
        when(network.insertFluid(any(), eq(250), eq(Action.PERFORM))).thenThrow(new IllegalStateException());
        var ledger = new MaterialTransferAuditLedger();
        try (var audits = mockStatic(MaterialTransferAuditLedger.class);
             var materials = mockStatic(MaterialSources.class)) {
            audits.when(() -> MaterialTransferAuditLedger.get(player.server)).thenReturn(ledger);
            assertEquals(FluidVoucherRecovery.RestoreResult.UNKNOWN, FluidVoucherRecovery.restore(player, voucher, network));
            var saved = ItemStack.of(voucher.save(new CompoundTag()));
            assertEquals(FluidVoucherRecovery.RestoreResult.UNKNOWN, FluidVoucherRecovery.restore(player, saved, network));
            assertEquals(1, ledger.pendingAttempts().size());
            var attempt = ledger.pendingAttempt(saved.getTag().getUUID("RecoveryAttempt"));
            assertEquals(owner, attempt.ownerId());
            assertEquals(250, attempt.amount());
            assertEquals("minecraft:water", attempt.resourceId().toString());
            assertEquals(250, FluidRefundItem.rawFluid(saved).getInt("Amount"));
            verify(network).insertFluid(any(), eq(250), eq(Action.PERFORM));
        }
    }

    @Test
    void rsTrackerCountsOnlyAcceptedFluidAndWaitingTasksCannotReceiveIt() {
        var voucher = FluidRefundItem.create(InkFluidTestFixtures.refundItem(), raw(250), null);
        var network = mock(INetwork.class);
        var player = mock(ServerPlayer.class);
        when(player.getInventory()).thenReturn(mock(Inventory.class));
        when(network.canRun()).thenReturn(true);
        IStorageTracker<FluidStack> tracker = mock(IStorageTracker.class);
        when(network.getFluidStorageTracker()).thenReturn(tracker);
        try (var waiting = mockStatic(TaskOutputReception.class); var materials = mockStatic(MaterialSources.class)) {
            waiting.when(() -> TaskOutputReception.hasWaitingResources(network)).thenReturn(true);
            when(network.insertFluid(any(), eq(250), eq(Action.PERFORM))).thenAnswer(call -> {
                assertFalse(NetworkOutputReception.canReceive(network));
                return new FluidStack(Fluids.WATER, 100);
            });
            assertEquals(FluidVoucherRecovery.RestoreResult.PARTIAL, FluidVoucherRecovery.restore(player, voucher, network));
            verify(tracker).changed(eq(player), argThat(fluid -> fluid.getAmount() == 150));
            assertTrue(NetworkOutputReception.canReceive(network));
            assertEquals(100, FluidRefundItem.rawFluid(voucher).getInt("Amount"));
        }
    }

    @Test
    void bdIndeterminateReceiptCannotBeRetriedAgainstAnotherBackend() {
        var voucher = FluidRefundItem.create(InkFluidTestFixtures.refundItem(), raw(90), null);
        var player = mock(ServerPlayer.class);
        when(player.getInventory()).thenReturn(mock(Inventory.class));
        when(player.getUUID()).thenReturn(UUID.randomUUID());
        var session = mock(StorageSession.class);
        when(session.hasPermission(player, StoragePermission.INSERT)).thenReturn(true);
        var token = InkFluidSupport.token(InkFluidTestFixtures.tokenItem(), new FluidStack(Fluids.WATER, 90));
        when(session.insert(player, token, false)).thenReturn(
                StorageOperationResult.indeterminateInsert(token, StorageDiagnosticCode.NATIVE_OPERATION_FAILED_AFTER_MUTATION));
        var ledger = new MaterialTransferAuditLedger();
        try (var tokens = mockStatic(FluidTokenItem.class);
             var audits = mockStatic(MaterialTransferAuditLedger.class);
             var materials = mockStatic(MaterialSources.class)) {
            tokens.when(() -> FluidTokenItem.token(any())).thenReturn(token);
            audits.when(() -> MaterialTransferAuditLedger.get(player.server)).thenReturn(ledger);
            assertEquals(FluidVoucherRecovery.RestoreResult.UNKNOWN,
                    FluidVoucherRecovery.restoreEndpoint(player, voucher, () -> session));
            INetwork network = mock(INetwork.class);
            assertEquals(FluidVoucherRecovery.RestoreResult.UNKNOWN, FluidVoucherRecovery.restore(player, voucher, network));
            verify(network, never()).insertFluid(any(), anyInt(), any());
            assertEquals(90, ledger.pendingAttempt(voucher.getTag().getUUID("RecoveryAttempt")).amount());
        }
    }
}
