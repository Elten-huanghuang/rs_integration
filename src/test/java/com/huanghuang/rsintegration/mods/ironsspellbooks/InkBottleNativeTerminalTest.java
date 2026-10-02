package com.huanghuang.rsintegration.mods.ironsspellbooks;

import com.huanghuang.rsintegration.mixin.refinedstorage.FluidContainerPreflightMixin;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import com.refinedmods.refinedstorage.api.network.INetwork;
import com.refinedmods.refinedstorage.api.network.grid.INetworkAwareGrid;
import com.refinedmods.refinedstorage.api.network.security.ISecurityManager;
import com.refinedmods.refinedstorage.api.network.security.Permission;
import com.refinedmods.refinedstorage.api.storage.cache.IStorageCache;
import com.refinedmods.refinedstorage.api.storage.tracker.IStorageTracker;
import com.refinedmods.refinedstorage.api.util.Action;
import com.refinedmods.refinedstorage.api.util.IStackList;
import com.refinedmods.refinedstorage.container.GridContainerMenu;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.fluids.FluidStack;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class InkBottleNativeTerminalTest extends BootstrapTest {
    @Test void nativeTerminalExtracts250MbDespiteVanillaOneBucketMinimum() throws Exception {
        Fixture f = new Fixture();
        assertTrue(f.click().isCancelled());
        verify(f.network).extractFluid(any(), eq(250), eq(Action.PERFORM));
        ArgumentCaptor<ItemStack> cursor = ArgumentCaptor.forClass(ItemStack.class);
        verify(f.menu).setCarried(cursor.capture());
        assertFalse(cursor.getValue().is(Items.GLASS_BOTTLE));
        assertEquals(1, cursor.getValue().getCount());
        verify(f.tracker).changed(eq(f.player), argThat(fluid -> fluid.isFluidEqual(f.ink) && fluid.getAmount() == 250));
    }

    @ParameterizedTest
    @ValueSource(strings = {"permission", "power", "grid", "menu", "network", "closed"})
    void nativeTerminalCannotBypassPermissionOrCurrentMenu(String invalid) throws Exception {
        Fixture f = new Fixture();
        switch (invalid) {
            case "permission" -> when(f.security.hasPermission(Permission.EXTRACT, f.player)).thenReturn(false);
            case "power" -> when(f.network.canRun()).thenReturn(false);
            case "grid" -> when(f.grid.isGridActive()).thenReturn(false);
            case "menu" -> when(f.menu.stillValid(f.player)).thenReturn(false);
            case "network" -> when(f.grid.getNetwork()).thenReturn(mock(INetwork.class));
            case "closed" -> f.player.containerMenu = null;
            default -> fail(invalid);
        }
        assertTrue(f.click().isCancelled());
        verify(f.network, never()).extractFluid(any(), anyInt(), any());
        verify(f.network, never()).extractItem(any(), anyInt(), any());
        verify(f.menu, never()).setCarried(any());
    }

    @Test void nonInkAndDeletedFluidKeepTheOriginalTerminalPath() throws Exception {
        Fixture f = new Fixture();
        when(f.list.get(f.id)).thenReturn(new FluidStack(Fluids.WATER, 1000));
        assertFalse(f.click().isCancelled());
        when(f.list.get(f.id)).thenReturn(null);
        assertFalse(f.click().isCancelled());
        verify(f.network, never()).extractFluid(any(), anyInt(), any());
    }

    private static final class Fixture {
        final INetwork network = mock(INetwork.class);
        final ServerPlayer player = mock(ServerPlayer.class);
        final GridContainerMenu menu = mock(GridContainerMenu.class);
        final INetworkAwareGrid grid = mock(INetworkAwareGrid.class);
        final ISecurityManager security = mock(ISecurityManager.class);
        final IStackList<FluidStack> list = mock(IStackList.class);
        final IStorageTracker<FluidStack> tracker = mock(IStorageTracker.class);
        final UUID id = UUID.randomUUID();
        final FluidStack ink = InkFluidTestFixtures.ink("common_ink", 250);
        final FluidContainerPreflightMixin mixin = new FluidContainerPreflightMixin() { };

        Fixture() throws Exception {
            Field field = FluidContainerPreflightMixin.class.getDeclaredField("network");
            field.setAccessible(true);
            field.set(mixin, network);
            IStorageCache<FluidStack> cache = mock(IStorageCache.class);
            player.containerMenu = menu;
            when(menu.getGrid()).thenReturn(grid);
            when(menu.getCarried()).thenReturn(new ItemStack(Items.GLASS_BOTTLE));
            when(menu.stillValid(player)).thenReturn(true);
            when(grid.getNetwork()).thenReturn(network);
            when(grid.isGridActive()).thenReturn(true);
            when(network.canRun()).thenReturn(true);
            when(network.getSecurityManager()).thenReturn(security);
            when(security.hasPermission(Permission.EXTRACT, player)).thenReturn(true);
            when(network.getFluidStorageCache()).thenReturn(cache);
            when(cache.getList()).thenReturn(list);
            when(list.get(id)).thenReturn(ink);
            when(network.extractFluid(any(), eq(250), any())).thenReturn(ink.copy());
            when(network.getFluidStorageTracker()).thenReturn(tracker);
        }

        CallbackInfo click() throws Exception {
            Method method = FluidContainerPreflightMixin.class.getDeclaredMethod("rsi$extractInkBottle",
                    ServerPlayer.class, UUID.class, boolean.class, CallbackInfo.class);
            method.setAccessible(true);
            CallbackInfo callback = new CallbackInfo("onExtract", true);
            method.invoke(mixin, player, id, false, callback);
            return callback;
        }
    }
}
