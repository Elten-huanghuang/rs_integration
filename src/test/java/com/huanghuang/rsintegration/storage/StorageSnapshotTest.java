package com.huanghuang.rsintegration.storage;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StorageSnapshotTest extends BootstrapTest {
    private static final StorageBackendId BACKEND = new StorageBackendId("test");

    @Test
    void exactCountsKeepNbtVariantsSeparateAndUseLongAmounts() {
        ItemStack red = namedDiamond("red");
        ItemStack blue = namedDiamond("blue");
        StorageItemKey redKey = key(red);
        StorageItemKey blueKey = key(blue);
        StorageSnapshot snapshot = new StorageSnapshot(BACKEND, List.of(
                new StoredItem(redKey, Long.MAX_VALUE - 2),
                new StoredItem(redKey, 10),
                new StoredItem(blueKey, 4)));

        assertEquals(Long.MAX_VALUE, snapshot.countExact(redKey));
        assertEquals(4, snapshot.countExact(blueKey));
        assertEquals(0, snapshot.countExact(key(new ItemStack(Items.DIAMOND))));
    }

    @Test
    void snapshotAndStoredItemsDefensivelyCopyMutableStacks() {
        ItemStack source = namedDiamond("original");
        StorageSnapshot snapshot = new StorageSnapshot(BACKEND,
                List.of(new StoredItem(key(source), 8)));
        source.getOrCreateTag().putString("variant", "changed");

        ItemStack returned = snapshot.items().get(0).stack();
        assertEquals("original", returned.getTag().getString("variant"));
        returned.getOrCreateTag().putString("variant", "also_changed");
        assertEquals("original", snapshot.items().get(0).stack().getTag().getString("variant"));
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.items().add(new StoredItem(key(new ItemStack(Items.APPLE)), 1)));
    }

    @Test
    void ingredientMatchingMayReturnMultipleExactVariants() {
        StorageSnapshot snapshot = new StorageSnapshot(BACKEND, List.of(
                new StoredItem(key(namedDiamond("a")), 3),
                new StoredItem(key(namedDiamond("b")), 5),
                new StoredItem(key(new ItemStack(Items.APPLE)), 7)));

        assertEquals(2, snapshot.match(Ingredient.of(Items.DIAMOND)).items().size());
    }

    @Test
    void backendIdentityKeepsEqualDisplayStacksSeparate() {
        ItemStack display = new ItemStack(Items.DIAMOND);
        CompoundTag firstIdentity = new CompoundTag();
        firstIdentity.putString("caps", "first");
        CompoundTag secondIdentity = new CompoundTag();
        secondIdentity.putString("caps", "second");
        StorageItemKey first = new StorageItemKey(BACKEND, firstIdentity, display);
        StorageItemKey second = new StorageItemKey(BACKEND, secondIdentity, display);
        StorageSnapshot snapshot = new StorageSnapshot(BACKEND, List.of(
                new StoredItem(first, 2), new StoredItem(second, 9)), 42);

        assertEquals(2, snapshot.countExact(first));
        assertEquals(9, snapshot.countExact(second));
        assertEquals(42, snapshot.revision());
    }

    private static ItemStack namedDiamond(String variant) {
        ItemStack stack = new ItemStack(Items.DIAMOND);
        CompoundTag tag = new CompoundTag();
        tag.putString("variant", variant);
        stack.setTag(tag);
        return stack;
    }

    private static StorageItemKey key(ItemStack stack) {
        return StorageItemKey.fromItemStack(BACKEND, stack);
    }

    @Test
    void duplicateCanonicalKeysAreAggregatedBeforeMatching() {
        StorageBackendId backend = new StorageBackendId("test");
        StorageItemKey key = StorageItemKey.fromItemStack(backend, new ItemStack(Items.APPLE));

        StorageSnapshot snapshot = new StorageSnapshot(backend, List.of(
                new StoredItem(key, 2), new StoredItem(key, 3)));

        assertEquals(1, snapshot.items().size());
        assertEquals(5, snapshot.items().get(0).amount());
        assertEquals(1, snapshot.match(Ingredient.of(Items.APPLE)).items().size());
    }

    @Test
    void snapshotRejectsItemsOwnedByAnotherBackend() {
        StorageBackendId foreign = new StorageBackendId("foreign");

        assertThrows(IllegalArgumentException.class, () -> new StorageSnapshot(BACKEND, List.of(
                new StoredItem(StorageItemKey.fromItemStack(
                        foreign, new ItemStack(Items.DIAMOND)), 1))));
        assertEquals(BACKEND, new StorageSnapshot(BACKEND, List.of()).backendId());
    }

    @Test
    void ingredientFailuresAndEmptyIngredientsAreStructured() {
        StorageSnapshot snapshot = new StorageSnapshot(BACKEND, List.of(
                new StoredItem(key(new ItemStack(Items.DIAMOND)), 1)));
        Ingredient throwingTest = new Ingredient(Stream.empty()) {
            @Override public boolean isEmpty() { return false; }
            @Override public boolean test(ItemStack stack) {
                throw new IllegalStateException("broken test");
            }
        };
        Ingredient throwingEmpty = new Ingredient(Stream.empty()) {
            @Override public boolean isEmpty() {
                throw new IllegalStateException("broken empty check");
            }
        };

        assertEquals(StorageSnapshot.MatchStatus.FAILED,
                snapshot.match(throwingTest).status());
        assertEquals(StorageDiagnosticCode.INGREDIENT_MATCH_FAILED,
                snapshot.match(throwingEmpty).diagnosticCode());
        assertEquals(StorageSnapshot.MatchStatus.EMPTY_INGREDIENT,
                snapshot.match(Ingredient.EMPTY).status());
    }
}
