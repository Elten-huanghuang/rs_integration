package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.storage.StorageBackendId;
import com.huanghuang.rsintegration.storage.StorageItemKey;
import com.huanghuang.rsintegration.storage.StorageSnapshot;
import com.huanghuang.rsintegration.storage.StoredItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CraftStorageReservationViewTest extends com.huanghuang.rsintegration.testutil.BootstrapTest {
    @Test
    void endpointSnapshotCannotBeOverbookedByRepeatedIngredientReservations() {
        StorageBackendId backend = new StorageBackendId("test");
        ItemStack slabs = new ItemStack(Items.OAK_SLAB, 2);
        List<StoredItem> matches = List.of(new StoredItem(
                StorageItemKey.fromItemStack(backend, slabs), slabs.getCount()));
        Map<CraftingResolver.StackKey, Integer> pending = new HashMap<>();

        assertEquals(1, ExtractionLedger.reserveEndpointMatches(matches, 1, pending)
                .template().getCount());
        assertEquals(1, ExtractionLedger.reserveEndpointMatches(matches, 1, pending)
                .template().getCount());
        assertTrue(ExtractionLedger.reserveEndpointMatches(matches, 1, pending)
                .template().isEmpty());
        assertEquals(2, pending.values().stream().mapToInt(Integer::intValue).sum());
    }

    @Test
    void reservationsReduceExactAvailabilityAndCanBeReleased() {
        StorageBackendId backend = new StorageBackendId("test");
        ItemStack stone = new ItemStack(Items.STONE, 10);
        StorageItemKey key = StorageItemKey.fromItemStack(backend, stone);
        CraftStorageReservationView view = new CraftStorageReservationView(
                new StorageSnapshot(backend, List.of(new StoredItem(key, 10))));

        assertEquals(6, view.reserveExact(stone, 6).getCount());
        assertEquals(4, view.availableExact(key));
        view.release(new ItemStack(Items.STONE, 2));
        assertEquals(6, view.availableExact(key));
    }

    @Test
    void matchingReservationsPreserveNbtIdentity() {
        StorageBackendId backend = new StorageBackendId("test");
        ItemStack stone = new ItemStack(Items.STONE, 5);
        StorageItemKey key = StorageItemKey.fromItemStack(backend, stone);
        CraftStorageReservationView view = new CraftStorageReservationView(
                new StorageSnapshot(backend, List.of(new StoredItem(key, 5))));

        ItemStack reserved = view.reserveMatching(Ingredient.of(Items.STONE), 3);
        assertTrue(ItemStack.isSameItemSameTags(stone, reserved));
        assertEquals(3, reserved.getCount());
    }
}
