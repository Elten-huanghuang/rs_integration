package com.huanghuang.rsintegration.mods.rs.monitor;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import com.refinedmods.refinedstorage.RSContainerMenus;
import com.refinedmods.refinedstorage.RSItems;
import com.refinedmods.refinedstorage.api.network.INetwork;
import com.refinedmods.refinedstorage.api.network.grid.GridType;
import com.refinedmods.refinedstorage.api.network.security.Permission;
import com.refinedmods.refinedstorage.api.storage.cache.IStorageCache;
import com.refinedmods.refinedstorage.api.storage.cache.IStorageCacheListener;
import com.refinedmods.refinedstorage.apiimpl.network.node.GridNetworkNode;
import com.refinedmods.refinedstorage.blockentity.data.BlockEntitySynchronizationManager;
import com.refinedmods.refinedstorage.blockentity.grid.GridBlockEntity;
import com.refinedmods.refinedstorage.screen.grid.GridScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraftforge.registries.RegistryObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RemoteCraftingGridTest extends BootstrapTest {
    private Field registryValue;
    private Object originalFilter;

    @Test
    void remoteScreenBindsLayoutProviderBeforeSlotInitialization() {
        var menu = mock(RemoteCraftingGridMenu.class);
        var inventory = mock(Inventory.class);
        Component title = Component.literal("远程合成终端");
        try (var screens = mockConstruction(GridScreen.class)) {
            GridScreen screen = RsMonitorNavigationClient.createRemoteScreen(menu, inventory, title);

            assertSame(screens.constructed().get(0), screen);
            verify(menu).setScreenInfoProvider(screen);
            verify(menu, never()).initSlots();
        }
    }

    @BeforeEach void registerNativeFilterForTest() throws Exception {
        registryValue = RegistryObject.class.getDeclaredField("value");
        registryValue.setAccessible(true);
        originalFilter = registryValue.get(RSItems.FILTER);
        registryValue.set(RSItems.FILTER, Items.PAPER);
    }

    @AfterEach void restoreNativeFilter() throws Exception {
        registryValue.set(RSItems.FILTER, originalFilter);
    }

    @Test void clientViewHasNativeSlotsAndSendsSettingsThroughTheExistingRsChannel() {
        var view = new RemoteCraftingGridView();
        assertEquals(GridType.CRAFTING, view.getGridType());
        assertEquals(9, view.getCraftingMatrix().getContainerSize());
        assertEquals(4, view.getFilter().getSlots());
        view.getCraftingMatrix().setItem(0, new ItemStack(Items.DIAMOND, 3));
        assertEquals(3, view.getCraftingMatrix().getItem(0).getCount());
        try (var sync = mockStatic(BlockEntitySynchronizationManager.class)) {
            view.onSortingTypeChanged(1);
            sync.verify(() -> BlockEntitySynchronizationManager.setParameter(GridBlockEntity.SORTING_TYPE, 1));
            view.onTabPageChanged(-1);
            view.onTabPageChanged(100);
            sync.verifyNoMoreInteractions();
        }
    }

    @Test void actualRemoteMenuKeepsTheRealInventoryAndCleansNativeListenersWhenTargetUnloads() throws Exception {
        var value = RegistryObject.class.getDeclaredField("value");
        value.setAccessible(true);
        Object originalType = value.get(RSContainerMenus.GRID);
        value.set(RSContainerMenus.GRID, mock(MenuType.class));
        try {
            var player = mock(ServerPlayer.class);
            var local = mock(ServerLevel.class);
            var remote = mock(ServerLevel.class);
            var network = mock(INetwork.class, RETURNS_DEEP_STUBS);
            var block = mock(GridBlockEntity.class);
            var node = mock(GridNetworkNode.class);
            var storage = mock(IStorageCache.class);
            var listener = mock(IStorageCacheListener.class);
            var contents = new RemoteCraftingGridView();
            BlockPos position = new BlockPos(10, 64, 20);
            GlobalPos terminal = GlobalPos.of(Level.NETHER, position);
            when(player.level()).thenReturn(local);
            when(player.getCommandSenderWorld()).thenReturn(local);
            when(player.getInventory()).thenReturn(new Inventory(player));
            when(block.getLevel()).thenReturn(remote);
            when(block.getBlockPos()).thenReturn(position);
            when(block.getNode()).thenReturn(node);
            when(block.getDataManager()).thenReturn(mock(BlockEntitySynchronizationManager.class));
            when(remote.isLoaded(position)).thenReturn(true);
            when(remote.getBlockEntity(position)).thenReturn(block);
            when(node.getNetwork()).thenReturn(network);
            when(node.getGridType()).thenReturn(GridType.CRAFTING);
            when(node.isGridActive()).thenReturn(true);
            when(node.getFilter()).thenReturn(contents.getFilter());
            when(node.getCraftingMatrix()).thenReturn(contents.getCraftingMatrix());
            when(node.getCraftingResult()).thenReturn(contents.getCraftingResult());
            when(node.getStorageCache()).thenReturn(storage);
            when(node.createListener(player)).thenReturn(listener);
            when(network.canRun()).thenReturn(true);
            when(network.getSecurityManager().hasPermission(any(), eq(player))).thenReturn(true);
            var menu = new RemoteCraftingGridMenu(node, block, player, 17, terminal);
            player.containerMenu = menu;

            assertSame(node, menu.getGrid());
            assertSame(block, menu.getBlockEntity());
            assertEquals(terminal, menu.terminal());
            assertTrue(menu.stillValid(player));
            assertSame(contents.getCraftingMatrix(), menu.getGrid().getCraftingMatrix());
            menu.broadcastChanges();
            menu.broadcastChanges();
            verify(storage, times(1)).addListener(listener);
            when(network.getSecurityManager().hasPermission(Permission.MODIFY, player)).thenReturn(false);
            assertFalse(menu.stillValid(player));
            when(network.getSecurityManager().hasPermission(Permission.MODIFY, player)).thenReturn(true);
            when(remote.isLoaded(position)).thenReturn(false);
            assertFalse(menu.stillValid(player));
            menu.removed(player);
            verify(storage).removeListener(listener);
            verify(node).removeCraftingListener(menu);
            verifyNoInteractions(local);
        } finally {
            value.set(RSContainerMenus.GRID, originalType);
        }
    }
}
