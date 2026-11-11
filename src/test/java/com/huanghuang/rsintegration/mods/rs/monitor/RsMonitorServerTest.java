package com.huanghuang.rsintegration.mods.rs.monitor;

import com.huanghuang.rsintegration.crafting.AsyncCraftChain;
import com.huanghuang.rsintegration.crafting.AsyncCraftManager;
import com.huanghuang.rsintegration.mixin.refinedstorage.CraftingManagerNetworkAccessor;
import com.huanghuang.rsintegration.storage.StorageBackendId;
import com.huanghuang.rsintegration.storage.StorageReference;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import com.refinedmods.refinedstorage.api.autocrafting.ICraftingManager;
import com.refinedmods.refinedstorage.api.network.INetwork;
import com.refinedmods.refinedstorage.api.network.security.ISecurityManager;
import com.refinedmods.refinedstorage.api.network.security.Permission;
import com.refinedmods.refinedstorage.blockentity.craftingmonitor.ICraftingMonitor;
import com.refinedmods.refinedstorage.container.CraftingMonitorContainerMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RsMonitorServerTest extends BootstrapTest {
    @Test void cancelsOnlyOwnedOrderInTheLiveMenuNetwork() {
        var fixture = new Fixture();
        try (var singleton = mockStatic(AsyncCraftManager.class)) {
            singleton.when(AsyncCraftManager::getInstance).thenReturn(fixture.manager);
            RsMonitorServer.cancel(fixture.player, fixture.packet);
            verify(fixture.chain).cancel(anyString(), any(Component.class));
            verify(fixture.nativeManager, never()).cancel(any());
            verify(fixture.nativeManager, never()).update();
        }
    }

    @Test void closedMenuAndLostPermissionRejectCancellation() {
        var fixture = new Fixture();
        fixture.player.containerMenu = null;
        RsMonitorServer.cancel(fixture.player, fixture.packet);
        fixture.player.containerMenu = fixture.menu;
        when(fixture.security.hasPermission(Permission.AUTOCRAFTING, fixture.player)).thenReturn(false);
        RsMonitorServer.cancel(fixture.player, fixture.packet);
        verify(fixture.chain, never()).cancel(anyString(), any(Component.class));
    }

    @Test void forgedSessionAndChangedNetworkRejectCancellationBeforeTaskLookup() {
        var fixture = new Fixture();
        RsMonitorServer.cancel(fixture.player, new RsMonitorCancelPacket(0, UUID.randomUUID(), fixture.craft));
        when(fixture.network.getPosition()).thenReturn(new BlockPos(10, 64, 0));
        RsMonitorServer.cancel(fixture.player, fixture.packet);
        verify(fixture.chain, never()).cancel(anyString(), any(Component.class));
    }

    @Test void taskOwnershipAndItsActualNetworkAreRechecked() {
        var fixture = new Fixture();
        try (var singleton = mockStatic(AsyncCraftManager.class)) {
            singleton.when(AsyncCraftManager::getInstance).thenReturn(fixture.manager);
            when(fixture.chain.belongsTo(fixture.owner)).thenReturn(false);
            RsMonitorServer.cancel(fixture.player, fixture.packet);
            when(fixture.chain.belongsTo(fixture.owner)).thenReturn(true);
            when(fixture.chain.monitorStorageReference()).thenReturn(
                    new StorageReference(new StorageBackendId("beyonddimensions"), fixture.reference.networkId()));
            RsMonitorServer.cancel(fixture.player, fixture.packet);
            verify(fixture.chain, never()).cancel(anyString(), any(Component.class));
        }
    }

    private static final class Fixture {
        final UUID owner = UUID.randomUUID();
        final UUID craft = UUID.randomUUID();
        final ServerPlayer player = mock(ServerPlayer.class);
        final CraftingMonitorContainerMenu menu = mock(CraftingMonitorContainerMenu.class,
                withSettings().extraInterfaces(MonitorMenuAccess.class));
        final ICraftingMonitor monitor = mock(ICraftingMonitor.class);
        final ICraftingManager nativeManager = mock(ICraftingManager.class,
                withSettings().extraInterfaces(CraftingManagerNetworkAccessor.class));
        final INetwork network = mock(INetwork.class);
        final ISecurityManager security = mock(ISecurityManager.class);
        final AsyncCraftChain chain = mock(AsyncCraftChain.class);
        final AsyncCraftManager manager = mock(AsyncCraftManager.class);
        final StorageReference reference;
        final RsMonitorCancelPacket packet;

        Fixture() {
            player.containerMenu = menu;
            when(player.getUUID()).thenReturn(owner);
            when(menu.stillValid(player)).thenReturn(true);
            when(menu.getCraftingMonitor()).thenReturn(monitor);
            when(monitor.getCraftingManager()).thenReturn(nativeManager);
            when(((CraftingManagerNetworkAccessor) nativeManager).rsi$getNetwork()).thenReturn(network);
            when(network.canRun()).thenReturn(true);
            when(network.getSecurityManager()).thenReturn(security);
            when(security.hasPermission(Permission.AUTOCRAFTING, player)).thenReturn(true);
            var level = mock(ServerLevel.class);
            when(level.dimension()).thenReturn(Level.OVERWORLD);
            when(network.getLevel()).thenReturn(level);
            when(network.getPosition()).thenReturn(new BlockPos(0, 64, 0));
            reference = RsMonitorServer.authorizedNetwork(player, menu);
            assertNotNull(reference);
            var state = new MonitorScopeState();
            var scope = state.update(reference, List.of(craft));
            when(((MonitorMenuAccess) menu).rsi$monitorScope()).thenReturn(state);
            when(manager.getCraft(craft)).thenReturn(chain);
            when(chain.belongsTo(owner)).thenReturn(true);
            when(chain.monitorStorageReference()).thenReturn(reference);
            packet = new RsMonitorCancelPacket(menu.containerId, scope.session(), craft);
            clearInvocations(nativeManager);
        }
    }
}
