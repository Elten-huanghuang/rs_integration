package com.huanghuang.rsintegration.feature.ftbquests;

import com.huanghuang.rsintegration.crafting.endpoint.CraftStorageEndpoint;
import com.huanghuang.rsintegration.storage.StorageBackendId;
import com.huanghuang.rsintegration.storage.StorageReference;
import com.huanghuang.rsintegration.storage.StorageSession;
import com.huanghuang.rsintegration.storage.StorageSnapshot;
import com.huanghuang.rsintegration.storage.StorageSnapshotResult;
import com.huanghuang.rsintegration.storage.StoredItem;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class QuestSubmissionEscrowTest extends BootstrapTest {
    @Test
    void combinesStoredAndInventoryItemsWithoutOverbookingAnotherTask() throws Exception {
        ServerPlayer player = mock(ServerPlayer.class);
        var serverField = ServerPlayer.class.getField("server");
        serverField.setAccessible(true);
        serverField.set(player, mock(MinecraftServer.class));
        Inventory inventory = new Inventory(player);
        inventory.setItem(0, new ItemStack(Items.IRON_INGOT, 4));
        when(player.getInventory()).thenReturn(inventory);
        StorageBackendId backend = new StorageBackendId("test");
        StorageSession session = mock(StorageSession.class, CALLS_REAL_METHODS);
        when(session.reference()).thenReturn(new StorageReference(backend, "quest-test"));
        ItemStack stored = new ItemStack(Items.IRON_INGOT, 6);
        StorageSnapshot snapshot = new StorageSnapshot(backend,
                List.of(new StoredItem(session.itemKey(stored), 6L)));
        when(session.snapshotItems(player)).thenReturn(StorageSnapshotResult.success(snapshot));
        CraftStorageEndpoint endpoint = () -> session;

        try (QuestSubmissionEscrow escrow = new QuestSubmissionEscrow(player, endpoint, null)) {
            assertTrue(escrow.reserve(1L, Ingredient.of(Items.IRON_INGOT), 10));
            assertFalse(escrow.reserve(2L, Ingredient.of(Items.IRON_INGOT), 1));
        }
    }
}
