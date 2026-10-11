package com.huanghuang.rsintegration.mods.ironsspellbooks;

import com.huanghuang.rsintegration.crafting.MaterialSources;
import com.huanghuang.rsintegration.network.RSIntegrationNetwork;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import com.refinedmods.refinedstorage.api.network.INetwork;
import com.refinedmods.refinedstorage.api.network.security.ISecurityManager;
import com.refinedmods.refinedstorage.api.network.security.Permission;
import com.refinedmods.refinedstorage.api.util.Action;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
            assertEquals(FluidRefundItem.RestoreResult.PARTIAL,
                    FluidRefundItem.restore(player, voucher, network));
            assertEquals(1, voucher.getCount());
            assertEquals(400, voucher.getTag().getCompound("Fluid").getInt("Amount"));
            assertEquals(FluidRefundItem.RestoreResult.NO_SPACE,
                    FluidRefundItem.restore(player, voucher, network));
            assertEquals(400, voucher.getTag().getCompound("Fluid").getInt("Amount"));
            when(network.insertFluid(any(), eq(400), eq(Action.PERFORM)))
                    .thenReturn(FluidStack.EMPTY);
            assertEquals(FluidRefundItem.RestoreResult.STORED,
                    FluidRefundItem.restore(player, voucher, network));
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
            assertEquals(FluidRefundItem.RestoreResult.STORED,
                    FluidRefundItem.restore(player, voucher, network));
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
        assertEquals(FluidRefundItem.RestoreResult.NO_PERMISSION,
                FluidRefundItem.restore(player, voucher, network));
        verify(network, never()).insertFluid(any(), anyInt(), any());
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
}
