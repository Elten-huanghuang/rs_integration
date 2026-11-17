package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.crafting.endpoint.CraftStorageEndpoint;
import com.huanghuang.rsintegration.crafting.endpoint.CraftStorageEndpoints;
import com.huanghuang.rsintegration.crafting.endpoint.SessionCraftStorageEndpoint;
import com.huanghuang.rsintegration.storage.StorageBackendId;
import com.huanghuang.rsintegration.storage.StorageReference;
import com.huanghuang.rsintegration.storage.StorageRestockSupport;
import com.huanghuang.rsintegration.storage.StorageSession;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import com.mojang.authlib.GameProfile;
import net.minecraft.server.level.ServerPlayer;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class CraftStorageSelectionServiceTest extends BootstrapTest {
    @Test
    void currentEndpointIsSharedByPreviewAndExecutionSelection() {
        ServerPlayer player = player();
        StorageSession session = mock(StorageSession.class);
        CraftStorageEndpoint endpoint = new SessionCraftStorageEndpoint(session);
        when(session.reference()).thenReturn(
                new StorageReference(new StorageBackendId("beyonddimensions"), "current"));

        try (var restock = mockStatic(StorageRestockSupport.class)) {
            restock.when(() -> StorageRestockSupport.resolve(player))
                    .thenReturn(Optional.of(endpoint));

            CraftStorageSelectionService.Selection selected =
                    CraftStorageSelectionService.select(player, null, null, null, null);

            assertSame(endpoint, selected.endpoint());
            assertSame(endpoint.session().reference(), selected.reference());
        }
    }

    @Test
    void unavailableExplicitReferenceDoesNotFallBackToAnotherNetwork() {
        ServerPlayer player = player();
        StorageReference requested =
                new StorageReference(new StorageBackendId("refinedstorage"), "v1|missing");

        try (var endpoints = mockStatic(CraftStorageEndpoints.class)) {
            endpoints.when(() -> CraftStorageEndpoints.resolve(requested, player))
                    .thenReturn(Optional.empty());

            CraftStorageSelectionService.Selection selected =
                    CraftStorageSelectionService.select(player, null, null, null, requested);

            assertTrue(selected.failed());
            assertSame(requested, selected.reference());
        }
    }

    private static ServerPlayer player() {
        ServerPlayer player = mock(ServerPlayer.class);
        when(player.getGameProfile()).thenReturn(
                new GameProfile(UUID.randomUUID(), "selection-test"));
        return player;
    }
}
