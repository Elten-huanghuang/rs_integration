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
    void distinctBackendIdentitiesSharingOneCraftingKeyChargePendingOnlyOnce() {
        var backend = new StorageBackendId("test");
        ItemStack display = new ItemStack(Items.IRON_INGOT);
        var firstIdentity = new net.minecraft.nbt.CompoundTag();
        firstIdentity.putString("identity", "first");
        var secondIdentity = new net.minecraft.nbt.CompoundTag();
        secondIdentity.putString("identity", "second");
        var matches = List.of(new StoredItem(new StorageItemKey(backend, firstIdentity, display), 2),
                new StoredItem(new StorageItemKey(backend, secondIdentity, display), 3));
        var key = CraftingResolver.StackKey.of(display, true);
        Map<CraftingResolver.StackKey, Integer> pending = new HashMap<>(Map.of(key, 3));
        assertEquals(2, ExtractionLedger.reserveEndpointMatches(matches, 2, pending).template().getCount());
        assertEquals(Map.of(key, 5), pending);
        assertTrue(ExtractionLedger.reserveEndpointMatches(matches, 1, pending).template().isEmpty());
    }

    @Test
    void insufficientReservationDoesNotPublishPartialAllocations() {
        ItemStack stack = new ItemStack(Items.IRON_INGOT, 3);
        var key = CraftingResolver.StackKey.of(stack, true);
        Map<CraftingResolver.StackKey, Integer> pending = new HashMap<>(Map.of(key, 1));
        var result = ExtractionLedger.reserveEndpointMatches(List.of(new StoredItem(
                StorageItemKey.fromItemStack(new StorageBackendId("test"), stack), 3)), 4, pending);
        assertTrue(result.template().isEmpty());
        assertEquals(Map.of(key, 1), pending);
    }

    @Test
    void successfulReservationDoesNotReadUnusedTail() {
        var first = new StoredItem(StorageItemKey.fromItemStack(
                new StorageBackendId("test"), new ItemStack(Items.IRON_INGOT)), 10);
        List<StoredItem> matches = new java.util.AbstractList<>() {
            @Override public int size() { return 1000; }
            @Override public StoredItem get(int index) {
                if (index != 0) throw new AssertionError("unnecessary tail scan");
                return first;
            }
        };
        assertEquals(2, ExtractionLedger.reserveEndpointMatches(matches, 2, new HashMap<>())
                .template().getCount());
    }

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
