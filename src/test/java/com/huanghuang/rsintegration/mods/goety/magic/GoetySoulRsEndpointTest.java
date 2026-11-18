package com.huanghuang.rsintegration.mods.goety.magic;

import com.huanghuang.rsintegration.crafting.endpoint.CraftStorageEndpoint;
import com.huanghuang.rsintegration.crafting.endpoint.SessionCraftStorageEndpoint;
import com.huanghuang.rsintegration.network.RSIntegrationNetwork;
import com.huanghuang.rsintegration.storage.StorageBackendId;
import com.huanghuang.rsintegration.storage.StorageReference;
import com.huanghuang.rsintegration.storage.StorageSession;
import com.huanghuang.rsintegration.storage.disk.rs.UnifiedDiskManager;
import com.huanghuang.rsintegration.storage.rs.RefinedStorageReference;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import com.refinedmods.refinedstorage.api.network.INetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class GoetySoulRsEndpointTest extends BootstrapTest {
    @Test
    void resolvesStructuredRefinedStorageEndpointAtControllerLocation() throws ReflectiveOperationException {
        ServerPlayer player = mock(ServerPlayer.class);
        MinecraftServer server = mock(MinecraftServer.class);
        var serverField = ServerPlayer.class.getField("server");
        serverField.setAccessible(true);
        serverField.set(player, server);

        ServerLevel level = mock(ServerLevel.class);
        BlockPos controllerPos = new BlockPos(16, 64, -32);
        INetwork network = mock(INetwork.class);
        when(server.isSameThread()).thenReturn(true);
        when(server.getLevel(Level.OVERWORLD)).thenReturn(level);
        when(level.dimension()).thenReturn(Level.OVERWORLD);
        when(level.isLoaded(controllerPos)).thenReturn(true);
        when(network.getLevel()).thenReturn(level);
        when(network.getPosition()).thenReturn(controllerPos);
        CraftStorageEndpoint endpoint = endpoint(RefinedStorageReference.fromNetwork(network));

        try (MockedStatic<RSIntegrationNetwork> networks = mockStatic(RSIntegrationNetwork.class)) {
            networks.when(() -> RSIntegrationNetwork.resolveNetworkStrict(
                            server, Level.OVERWORLD, controllerPos))
                    .thenReturn(network);
            assertSame(network, GoetySoulCraftingTask.resolveRsNetwork(player, null, endpoint));
        }
    }

    @Test
    void doesNotReinterpretAnotherBackendAsRefinedStorage() {
        ServerPlayer player = mock(ServerPlayer.class);
        CraftStorageEndpoint endpoint = endpoint(new StorageReference(
                new StorageBackendId("beyonddimensions"), "21"));
        INetwork legacyRs = mock(INetwork.class);

        try (MockedStatic<RSIntegrationNetwork> current = mockStatic(RSIntegrationNetwork.class)) {
            assertNull(GoetySoulCraftingTask.resolveRsNetwork(player, legacyRs, endpoint));
            current.verifyNoInteractions();
        }
    }

    @Test
    void legacyRefinedStorageNetworkRemainsUsableWithoutStructuredEndpoint() {
        ServerPlayer player = mock(ServerPlayer.class);
        INetwork legacyRs = mock(INetwork.class);

        try (MockedStatic<UnifiedDiskManager> disks =
                     mockStatic(UnifiedDiskManager.class)) {
            disks.when(() -> UnifiedDiskManager.existing(null)).thenReturn(null);
            assertEquals(0,
                    GoetySoulCraftingTask.availableInStorage(player, legacyRs, null));
        }
    }

    private static CraftStorageEndpoint endpoint(StorageReference reference) {
        StorageSession session = mock(StorageSession.class);
        when(session.reference()).thenReturn(reference);
        return new SessionCraftStorageEndpoint(session);
    }
}
