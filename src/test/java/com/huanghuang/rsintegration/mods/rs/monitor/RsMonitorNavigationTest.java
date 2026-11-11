package com.huanghuang.rsintegration.mods.rs.monitor;

import com.huanghuang.rsintegration.crafting.AsyncCraftChain;
import com.huanghuang.rsintegration.crafting.AsyncCraftManager;
import com.huanghuang.rsintegration.network.RSIntegrationNetwork;
import com.huanghuang.rsintegration.storage.StorageBackendId;
import com.huanghuang.rsintegration.storage.StorageReference;
import com.huanghuang.rsintegration.storage.rs.RefinedStorageBackend;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import com.huanghuang.rsintegration.mods.rs.monitor.RsMonitorNavigationServer.Result;
import com.refinedmods.refinedstorage.api.IRSAPI;
import com.refinedmods.refinedstorage.api.autocrafting.ICraftingManager;
import com.refinedmods.refinedstorage.api.autocrafting.task.ICraftingTask;
import com.refinedmods.refinedstorage.api.network.INetwork;
import com.refinedmods.refinedstorage.api.network.INetworkNodeGraphEntry;
import com.refinedmods.refinedstorage.api.network.grid.GridType;
import com.refinedmods.refinedstorage.api.network.grid.IGridManager;
import com.refinedmods.refinedstorage.apiimpl.API;
import com.refinedmods.refinedstorage.apiimpl.network.grid.factory.GridBlockGridFactory;
import com.refinedmods.refinedstorage.apiimpl.network.node.GridNetworkNode;
import com.refinedmods.refinedstorage.api.network.security.Permission;
import com.refinedmods.refinedstorage.blockentity.grid.GridBlockEntity;
import com.refinedmods.refinedstorage.container.CraftingMonitorContainerMenu;
import com.refinedmods.refinedstorage.container.GridContainerMenu;
import com.refinedmods.refinedstorage.screen.BaseScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RsMonitorNavigationTest extends BootstrapTest {
    @Test void opensNativeMenuWithoutAnyMonitorBlockOrItem() {
        var fixture = new Fixture();
        try (var menus = mockStatic(RsMonitorMenus.class)) {
            assertEquals(Result.OPENED, RsMonitorNavigationServer.openMonitor(fixture.player, fixture.network));
            menus.verify(() -> RsMonitorMenus.open(fixture.player, fixture.network));
            verify(fixture.player, never()).getInventory();
            verify(fixture.network, never()).getNodeGraph();
        }
    }

    @Test void directMonitorEntryFindsTheSameNetworkTerminalAndUsesNativeGridManager() {
        var fixture = new Fixture();
        BlockPos terminal = new BlockPos(30, 64, 0);
        fixture.terminals(fixture.terminal(terminal, fixture.network, GridType.CRAFTING));
        var api = mock(IRSAPI.class);
        var gridManager = mock(IGridManager.class);
        when(api.getGridManager()).thenReturn(gridManager);
        try (var blocks = mockStatic(RsMonitorNavigationServer.class, CALLS_REAL_METHODS);
             var rsApi = mockStatic(API.class)) {
            blocks.when(() -> RsMonitorNavigationServer.canOpenBlock(fixture.player, terminal)).thenReturn(true);
            rsApi.when(API::instance).thenReturn(api);
            doAnswer(call -> { fixture.player.containerMenu = mock(GridContainerMenu.class); return null; })
                    .when(gridManager).openGrid(GridBlockGridFactory.ID, fixture.player, terminal);
            assertEquals(Result.OPENED, RsMonitorNavigationServer.returnToTerminal(fixture.player, 0, null));
            verify(gridManager).openGrid(GridBlockGridFactory.ID, fixture.player, terminal);
        }
    }

    @Test void prefersOriginalTerminalButNeverReturnsToAnotherNetworkOrPatternGrid() {
        var fixture = new Fixture();
        BlockPos original = new BlockPos(30, 64, 0);
        BlockPos nearby = new BlockPos(1, 64, 0);
        BlockPos foreign = new BlockPos(2, 64, 0);
        BlockPos pattern = new BlockPos(3, 64, 0);
        fixture.terminals(fixture.terminal(original, fixture.network, GridType.CRAFTING),
                fixture.terminal(nearby, fixture.network, GridType.CRAFTING),
                fixture.terminal(foreign, mock(INetwork.class), GridType.CRAFTING),
                fixture.terminal(pattern, fixture.network, GridType.PATTERN));
        GlobalPos originalTarget = GlobalPos.of(Level.OVERWORLD, original);
        GlobalPos nearbyTarget = GlobalPos.of(Level.OVERWORLD, nearby);
        assertEquals(originalTarget, RsMonitorNavigationServer.findTerminal(fixture.player, fixture.network, originalTarget));
        assertEquals(nearbyTarget, RsMonitorNavigationServer.findTerminal(fixture.player, fixture.network, GlobalPos.of(Level.OVERWORLD, foreign)));
        assertEquals(nearbyTarget, RsMonitorNavigationServer.findTerminal(fixture.player, fixture.network, GlobalPos.of(Level.OVERWORLD, pattern)));
        when(fixture.level.isLoaded(original)).thenReturn(false);
        assertEquals(nearbyTarget, RsMonitorNavigationServer.findTerminal(fixture.player, fixture.network, originalTarget));
    }

    @Test void returnRejectsStaleMenuAndLostPermissionsBeforeTerminalSearch() {
        var fixture = new Fixture();
        assertEquals(Result.UNAVAILABLE, RsMonitorNavigationServer.returnToTerminal(fixture.player, 99, null));
        when(fixture.network.getSecurityManager().hasPermission(Permission.MODIFY, fixture.player)).thenReturn(false);
        assertEquals(Result.DENIED, RsMonitorNavigationServer.returnToTerminal(fixture.player, 0, null));
        verify(fixture.network, never()).getNodeGraph();
    }

    @Test void previewReferenceRequiresCurrentAccessAndCannotFallBackToAnotherNetwork() {
        var fixture = new Fixture();
        var request = new RsOpenMonitorForCraftPacket(1, fixture.reference, null);
        try (var access = mockStatic(RSIntegrationNetwork.class);
             var resolver = mockStatic(RefinedStorageBackend.class);
             var menus = mockStatic(RsMonitorMenus.class)) {
            assertEquals(Result.DENIED, RsMonitorNavigationServer.openForTask(fixture.player, request));
            resolver.verifyNoInteractions();
            access.when(() -> RSIntegrationNetwork.hasCurrentNetworkAccess(fixture.player, fixture.reference)).thenReturn(true);
            resolver.when(() -> RefinedStorageBackend.resolveNativeNetwork(fixture.reference, fixture.player))
                    .thenReturn(fixture.network);
            assertEquals(Result.OPENED, RsMonitorNavigationServer.openForTask(fixture.player, request));
            menus.verify(() -> RsMonitorMenus.open(fixture.player, fixture.network));
        }
    }

    @Test void activeCraftUsesItsServerNetworkAndRejectsOtherPlayersCrafts() {
        var fixture = new Fixture();
        UUID craft = UUID.randomUUID();
        var chain = mock(AsyncCraftChain.class);
        var manager = mock(AsyncCraftManager.class);
        when(manager.getCraft(craft)).thenReturn(chain);
        when(chain.monitorStorageReference()).thenReturn(fixture.reference);
        try (var singleton = mockStatic(AsyncCraftManager.class);
             var resolver = mockStatic(RefinedStorageBackend.class);
             var menus = mockStatic(RsMonitorMenus.class)) {
            singleton.when(AsyncCraftManager::getInstance).thenReturn(manager);
            var request = new RsOpenMonitorForCraftPacket(1, null, craft);
            assertEquals(Result.UNAVAILABLE, RsMonitorNavigationServer.openForTask(fixture.player, request));
            resolver.verifyNoInteractions();
            when(chain.belongsTo(fixture.player.getUUID())).thenReturn(true);
            resolver.when(() -> RefinedStorageBackend.resolveNativeNetwork(fixture.reference, fixture.player))
                    .thenReturn(fixture.network);
            assertEquals(Result.OPENED, RsMonitorNavigationServer.openForTask(fixture.player, request));
            menus.verify(() -> RsMonitorMenus.open(fixture.player, fixture.network));
            when(chain.isDone()).thenReturn(true);
            assertEquals(Result.UNAVAILABLE, RsMonitorNavigationServer.openForTask(fixture.player, request));
        }
    }

    @Test void bdTargetUsesGenericMonitorWithoutResolvingRs() {
        var fixture = new Fixture();
        try (var resolver = mockStatic(RefinedStorageBackend.class)) {
            assertEquals(Result.GENERIC, RsMonitorNavigationServer.openForTask(fixture.player,
                    new RsOpenMonitorForCraftPacket(1, new StorageReference(new StorageBackendId("beyonddimensions"), "7"), null)));
            resolver.verifyNoInteractions();
        }
    }

    @Test void returnButtonIsAvailableWithoutAnOriginalTerminal() {
        var menu = mock(CraftingMonitorContainerMenu.class);
        var navigation = mock(MonitorNavigationAccess.class);
        var button = new RsMonitorReturnButton(mock(BaseScreen.class), menu, navigation);
        assertNull(navigation.rsi$returnTerminal());
        assertTrue(button.visible);
        assertTrue(button.active);
    }

    @Test void dataAdapterUsesNativeTasksAndRetainsManagerForListenerCleanupAfterDisconnection() {
        var fixture = new Fixture();
        ICraftingTask task = mock(ICraftingTask.class);
        when(fixture.nativeManager.getTasks()).thenReturn(List.of(task));
        var monitor = (NetworkCraftingMonitor) fixture.menu.getCraftingMonitor();
        try (var resolver = mockStatic(RefinedStorageBackend.class)) {
            resolver.when(() -> RefinedStorageBackend.resolveNativeNetwork(fixture.reference, fixture.player)).thenReturn(fixture.network);
            assertEquals(List.of(task), monitor.getTasks());
            UUID taskId = UUID.randomUUID();
            monitor.onCancelled(fixture.player, taskId);
            verify(fixture.network.getItemGridHandler()).onCraftingCancelRequested(fixture.player, taskId);
            when(fixture.network.canRun()).thenReturn(false);
            assertFalse(monitor.stillValid(fixture.player));
            assertTrue(monitor.getTasks().isEmpty());
            assertSame(fixture.nativeManager, monitor.getCraftingManager());
            monitor.onCancelled(fixture.player, taskId);
            verify(fixture.network.getItemGridHandler(), times(1)).onCraftingCancelRequested(fixture.player, taskId);
        }
    }

    @Test void clientSelectionRequiresNoMonitorItemAndUpdatesTheMenuSelection() {
        var selected = new AtomicReference<Optional<UUID>>();
        var monitor = new NetworkCraftingMonitor(selected::set);
        UUID task = UUID.randomUUID();
        monitor.onTabSelectionChanged(Optional.of(task));
        assertEquals(Optional.of(task), selected.get());
        assertEquals(selected.get(), monitor.getTabSelected());
        assertEquals(-1, monitor.getSlotId());
        assertNull(monitor.getRedstoneModeParameter());
        monitor.onTabSelectionChanged(Optional.empty());
        assertEquals(Optional.empty(), selected.get());
    }

    @Test void nativeMenuAttachesOnceAndRemovesItsListenerAfterNetworkShutdown() {
        var fixture = new Fixture();
        when(fixture.player.level()).thenReturn(fixture.level);
        when(fixture.player.getCommandSenderWorld()).thenReturn(fixture.level);
        var monitor = (NetworkCraftingMonitor) fixture.menu.getCraftingMonitor();
        var menu = new NetworkCraftingMonitorMenu(mock(MenuType.class), monitor, fixture.player, 17);
        fixture.player.containerMenu = menu;

        menu.broadcastChanges();
        menu.broadcastChanges();
        verify(fixture.nativeManager, times(1)).addListener(menu);
        when(fixture.network.canRun()).thenReturn(false);
        assertFalse(menu.stillValid(fixture.player));
        menu.removed(fixture.player);
        verify(fixture.nativeManager, times(1)).removeListener(menu);
    }

    @Test void crossDimensionReturnUsesTheRealRemoteTerminalInsteadOfTheSameCoordinatesLocally() {
        var fixture = new Fixture();
        var nether = mock(ServerLevel.class);
        when(nether.dimension()).thenReturn(Level.NETHER);
        when(fixture.server.getLevel(Level.NETHER)).thenReturn(nether);
        BlockPos position = new BlockPos(30, 64, 0);
        var remoteNode = fixture.terminal(nether, position, fixture.network, GridType.CRAFTING);
        fixture.terminals(remoteNode, fixture.terminal(position, mock(INetwork.class), GridType.CRAFTING));
        GlobalPos remote = GlobalPos.of(Level.NETHER, position);
        GridBlockEntity block = (GridBlockEntity) nether.getBlockEntity(position);
        try (var menus = mockStatic(RsMonitorMenus.class); var api = mockStatic(API.class)) {
            menus.when(() -> RsMonitorMenus.openTerminal(fixture.player, block)).thenAnswer(call -> {
                fixture.player.containerMenu = mock(GridContainerMenu.class);
                return null;
            });
            assertEquals(remote, RsMonitorNavigationServer.findTerminal(fixture.player, fixture.network, null));
            assertEquals(Result.OPENED, RsMonitorNavigationServer.returnToTerminal(fixture.player, 0, remote));
            menus.verify(() -> RsMonitorMenus.openTerminal(fixture.player, block));
            api.verifyNoInteractions();
        }
        when(nether.isLoaded(position)).thenReturn(false);
        assertNull(RsMonitorNavigationServer.findTerminal(fixture.player, fixture.network, remote));
        when(nether.isLoaded(position)).thenReturn(true);
        when(remoteNode.getNode().getNetwork()).thenReturn(mock(INetwork.class));
        assertNull(RsMonitorNavigationServer.findTerminal(fixture.player, fixture.network, remote));
    }

    @Test void sameDimensionTerminalOutsideClientChunksAlsoUsesRemoteMenu() {
        var fixture = new Fixture();
        BlockPos position = new BlockPos(1000, 64, 1000);
        fixture.terminals(fixture.terminal(position, fixture.network, GridType.CRAFTING));
        GridBlockEntity block = (GridBlockEntity) fixture.level.getBlockEntity(position);
        try (var blocks = mockStatic(RsMonitorNavigationServer.class, CALLS_REAL_METHODS);
             var menus = mockStatic(RsMonitorMenus.class); var api = mockStatic(API.class)) {
            blocks.when(() -> RsMonitorNavigationServer.canOpenBlock(fixture.player, position)).thenReturn(false);
            menus.when(() -> RsMonitorMenus.openTerminal(fixture.player, block)).thenAnswer(call -> {
                fixture.player.containerMenu = mock(GridContainerMenu.class);
                return null;
            });
            assertEquals(Result.OPENED, RsMonitorNavigationServer.returnToTerminal(fixture.player, 0, null));
            menus.verify(() -> RsMonitorMenus.openTerminal(fixture.player, block));
            api.verifyNoInteractions();
        }
    }

    private static final class Fixture {
        final ServerPlayer player = mock(ServerPlayer.class);
        final ServerLevel level = mock(ServerLevel.class);
        final MinecraftServer server = mock(MinecraftServer.class);
        final INetwork network = mock(INetwork.class, RETURNS_DEEP_STUBS);
        final ICraftingManager nativeManager = mock(ICraftingManager.class);
        final CraftingMonitorContainerMenu menu = mock(CraftingMonitorContainerMenu.class);
        final StorageReference reference = new StorageReference(new StorageBackendId("refinedstorage"), "v1|minecraft:overworld@0,64,0");

        Fixture() {
            when(player.getUUID()).thenReturn(UUID.randomUUID());
            when(player.serverLevel()).thenReturn(level);
            when(player.getServer()).thenReturn(server);
            when(server.getLevel(Level.OVERWORLD)).thenReturn(level);
            when(player.position()).thenReturn(new Vec3(0, 64, 0));
            when(level.dimension()).thenReturn(Level.OVERWORLD);
            when(network.getLevel()).thenReturn(level);
            when(network.getPosition()).thenReturn(new BlockPos(0, 64, 0));
            when(network.canRun()).thenReturn(true);
            when(network.getCraftingManager()).thenReturn(nativeManager);
            when(network.getSecurityManager().hasPermission(any(), eq(player))).thenReturn(true);
            var monitor = new NetworkCraftingMonitor(network, player);
            when(menu.getCraftingMonitor()).thenReturn(monitor);
            when(menu.stillValid(player)).thenReturn(true);
            player.containerMenu = menu;
        }

        INetworkNodeGraphEntry terminal(BlockPos position, INetwork target, GridType type) {
            return terminal(level, position, target, type);
        }

        INetworkNodeGraphEntry terminal(ServerLevel targetLevel, BlockPos position, INetwork target, GridType type) {
            var node = mock(GridNetworkNode.class);
            when(node.getGridType()).thenReturn(type);
            when(node.getNetwork()).thenReturn(target);
            when(node.getLevel()).thenReturn(targetLevel);
            when(node.getPos()).thenReturn(position);
            when(node.isGridActive()).thenReturn(true);
            var block = mock(GridBlockEntity.class);
            when(block.getNode()).thenReturn(node);
            when(block.getLevel()).thenReturn(targetLevel);
            when(block.getBlockPos()).thenReturn(position);
            when(targetLevel.isLoaded(position)).thenReturn(true);
            when(targetLevel.getBlockEntity(position)).thenReturn(block);
            var entry = mock(INetworkNodeGraphEntry.class);
            when(entry.getNode()).thenReturn(node);
            return entry;
        }

        void terminals(INetworkNodeGraphEntry... nodes) {
            when(network.getNodeGraph().all()).thenReturn(List.of(nodes));
        }
    }
}
