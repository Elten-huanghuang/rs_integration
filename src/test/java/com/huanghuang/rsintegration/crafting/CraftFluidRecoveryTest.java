package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.mods.ironsspellbooks.FluidRefundItem;
import com.huanghuang.rsintegration.mods.ironsspellbooks.InkFluidSupport;
import com.huanghuang.rsintegration.mods.ironsspellbooks.InkFluidTestFixtures;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import com.huanghuang.rsintegration.unifiedgrid.UnifiedGridFluidRecovery;
import com.huanghuang.rsintegration.util.PlayerUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.fluids.FluidStack;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CraftFluidRecoveryTest extends BootstrapTest {
    @Test
    void onlineRefundBecomesOneNormalItemContainingTheFullAmount() throws Exception {
        ServerPlayer player = player();
        Inventory inventory = mock(Inventory.class);
        Level level = mock(Level.class);
        when(player.getInventory()).thenReturn(inventory);
        when(inventory.getFreeSlot()).thenReturn(0);
        when(player.level()).thenReturn(level);
        when(player.blockPosition()).thenReturn(BlockPos.ZERO);
        when(level.hasChunkAt(BlockPos.ZERO)).thenReturn(true);
        ItemStack token = token(1000);
        ItemStack voucher = new ItemStack(Items.PAPER);

        try (MockedStatic<FluidRefundItem> items = mockStatic(FluidRefundItem.class);
             MockedStatic<PlayerUtils> delivery = mockStatic(PlayerUtils.class)) {
            items.when(() -> FluidRefundItem.create(any(CompoundTag.class), isNull()))
                    .thenReturn(voucher);
            CraftFluidRecovery.retainToEndpoint(player, token, null);
            ArgumentCaptor<CompoundTag> captured = ArgumentCaptor.forClass(CompoundTag.class);
            items.verify(() -> FluidRefundItem.create(captured.capture(), isNull()));
            assertEquals(1000, captured.getValue().getInt("Amount"));
            delivery.verify(() -> PlayerUtils.safeGiveToStorage(player, voucher, null));
        }
    }

    @Test
    void fullInventoryKeepsTheRefundInTheWorldSave() throws Exception {
        ServerPlayer player = player();
        Inventory inventory = mock(Inventory.class);
        when(player.getInventory()).thenReturn(inventory);
        when(inventory.getFreeSlot()).thenReturn(-1);
        UUID owner = player.getUUID();
        UnifiedGridFluidRecovery recovery = mock(UnifiedGridFluidRecovery.class);
        when(recovery.retainRaw(eq(owner), any(CompoundTag.class), isNull())).thenReturn(true);
        try (MockedStatic<UnifiedGridFluidRecovery> savedData = mockStatic(UnifiedGridFluidRecovery.class)) {
            savedData.when(() -> UnifiedGridFluidRecovery.get(player.server)).thenReturn(recovery);
            CraftFluidRecovery.retainToEndpoint(player, token(1000), null);
        }
        ArgumentCaptor<CompoundTag> captured = ArgumentCaptor.forClass(CompoundTag.class);
        verify(recovery).retainRaw(eq(owner), captured.capture(), isNull());
        assertEquals(1000, captured.getValue().getInt("Amount"));
        verify(player, never()).level();
    }

    @Test
    void legacyTokenTurnsIntoTheNewRefundItem() throws Exception {
        ServerPlayer player = player();
        ItemStack token = token(250);
        ItemStack voucher = new ItemStack(Items.PAPER);
        when(player.getItemInHand(InteractionHand.MAIN_HAND)).thenReturn(token);
        try (MockedStatic<FluidRefundItem> items = mockStatic(FluidRefundItem.class)) {
            items.when(() -> FluidRefundItem.create(any(CompoundTag.class), isNull()))
                    .thenReturn(voucher);
            InkFluidTestFixtures.tokenItem().use(mock(Level.class), player, InteractionHand.MAIN_HAND);
            ArgumentCaptor<CompoundTag> captured = ArgumentCaptor.forClass(CompoundTag.class);
            items.verify(() -> FluidRefundItem.create(captured.capture(), isNull()));
            assertEquals(250, captured.getValue().getInt("Amount"));
        }
        verify(player).setItemInHand(InteractionHand.MAIN_HAND, voucher);
    }

    private static ServerPlayer player() throws Exception {
        ServerPlayer player = mock(ServerPlayer.class);
        MinecraftServer server = mock(MinecraftServer.class);
        Field serverField = ServerPlayer.class.getField("server");
        serverField.setAccessible(true);
        serverField.set(player, server);
        when(player.getUUID()).thenReturn(UUID.randomUUID());
        return player;
    }

    private static ItemStack token(int amount) {
        return InkFluidSupport.token(InkFluidTestFixtures.tokenItem(),
                new FluidStack(Fluids.WATER, amount));
    }
}
