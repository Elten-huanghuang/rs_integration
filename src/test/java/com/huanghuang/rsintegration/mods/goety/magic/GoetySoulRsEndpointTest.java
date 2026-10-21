package com.huanghuang.rsintegration.mods.goety.magic;

import com.huanghuang.rsintegration.crafting.endpoint.CraftStorageEndpoint;
import com.huanghuang.rsintegration.crafting.endpoint.SessionCraftStorageEndpoint;
import com.huanghuang.rsintegration.storage.disk.rs.UnifiedDiskManager;
import com.huanghuang.rsintegration.network.RSIntegrationNetwork;
import com.huanghuang.rsintegration.storage.StorageBackendId;
import com.huanghuang.rsintegration.storage.StorageReference;
import com.huanghuang.rsintegration.storage.StorageSession;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import com.refinedmods.refinedstorage.api.network.INetwork;
import net.minecraft.server.level.ServerPlayer;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class GoetySoulRsEndpointTest extends BootstrapTest {
    @Test
    void resolvesStructuredRefinedStorageEndpointThroughCurrentNetwork() {
        ServerPlayer player = mock(ServerPlayer.class);
        INetwork network = mock(INetwork.class);
        CraftStorageEndpoint endpoint = endpoint("refinedstorage");

        try (MockedStatic<RSIntegrationNetwork> current = mockStatic(RSIntegrationNetwork.class)) {
            current.when(() -> RSIntegrationNetwork.resolveCurrentNetworkFromPlayer(player))
                    .thenReturn(network);
            assertSame(network, GoetySoulCraftingTask.resolveRsNetwork(player, null, endpoint));
        }
    }

    @Test
    void doesNotReinterpretAnotherBackendAsRefinedStorage() {
        ServerPlayer player = mock(ServerPlayer.class);
        CraftStorageEndpoint endpoint = endpoint("beyonddimensions");
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

    private static CraftStorageEndpoint endpoint(String backend) {
        StorageSession session = mock(StorageSession.class);
        when(session.reference()).thenReturn(new StorageReference(
                new StorageBackendId(backend), backend + "-network"));
        return new SessionCraftStorageEndpoint(session);
    }
}
