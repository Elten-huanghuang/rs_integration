package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.storage.StorageBackendId;
import com.huanghuang.rsintegration.storage.StorageItemKey;
import com.huanghuang.rsintegration.storage.StorageOperationResult;
import com.huanghuang.rsintegration.storage.StorageOperationStatus;
import com.huanghuang.rsintegration.storage.StoragePermission;
import com.huanghuang.rsintegration.storage.StoragePermissionResult;
import com.huanghuang.rsintegration.storage.StorageReference;
import com.huanghuang.rsintegration.storage.StorageSession;
import com.huanghuang.rsintegration.storage.StorageSnapshotResult;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class CraftStorageEndpointTest extends BootstrapTest {
    @Test
    void delegatesItemOperationsToResolvedSession() {
        StorageSession session = new RecordingSession();
        CraftStorageEndpoint endpoint = new SessionCraftStorageEndpoint(session);

        assertSame(session, endpoint.session());
        assertEquals(StorageOperationStatus.SUCCESS,
                endpoint.insert(null, new ItemStack(Items.STONE), true).status());
    }

    @Test
    void backendNeutralEndpointDoesNotExposeAnRsNetwork() throws Exception {
        StorageReference reference = new StorageReference(
                new StorageBackendId("beyonddimensions"), "bd-network");
        StorageSession session = new RecordingSession(reference);
        CraftStorageEndpoint endpoint = new SessionCraftStorageEndpoint(session);

        Object legacyNetwork = CraftStorageEndpoints.class
                .getMethod("legacyNetwork", CraftStorageEndpoint.class)
                .invoke(null, endpoint);
        assertNull(legacyNetwork,
                "BD endpoint must not be reinterpreted as an RS INetwork");
    }

    private static final class RecordingSession implements StorageSession {
        private final StorageReference reference;

        private RecordingSession() {
            this(new StorageReference(new StorageBackendId("test"), "test-network"));
        }

        private RecordingSession(StorageReference reference) {
            this.reference = reference;
        }

        @Override public StorageReference reference() { return reference; }
        @Override public StorageSnapshotResult snapshotItems(ServerPlayer player) { return StorageSnapshotResult.failure(com.huanghuang.rsintegration.storage.StorageSnapshotStatus.UNAVAILABLE); }
        @Override public StoragePermissionResult checkPermission(ServerPlayer player, StoragePermission permission) { return StoragePermissionResult.allowed(); }
        @Override public StorageOperationResult extractExact(ServerPlayer player, StorageItemKey key, long amount, boolean simulate) {
            return StorageOperationResult.extracted(simulate ? com.huanghuang.rsintegration.storage.StorageOperationMode.SIMULATE : com.huanghuang.rsintegration.storage.StorageOperationMode.PERFORM, amount, List.of());
        }
        @Override public StorageOperationResult extractMatching(ServerPlayer player, Ingredient ingredient, long amount, boolean simulate) {
            return extractExact(player, itemKey(new ItemStack(Items.STONE)), amount, simulate);
        }
        @Override public StorageOperationResult insert(ServerPlayer player, ItemStack stack, boolean simulate) {
            return StorageOperationResult.inserted(simulate ? com.huanghuang.rsintegration.storage.StorageOperationMode.SIMULATE : com.huanghuang.rsintegration.storage.StorageOperationMode.PERFORM, stack, ItemStack.EMPTY);
        }
    }
}
