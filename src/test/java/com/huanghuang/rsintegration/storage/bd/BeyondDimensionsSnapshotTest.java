package com.huanghuang.rsintegration.storage.bd;

import com.huanghuang.rsintegration.storage.StorageBackendId;
import com.huanghuang.rsintegration.storage.StorageItemKey;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class BeyondDimensionsSnapshotTest extends BootstrapTest {
    private static final StorageBackendId BACKEND = new StorageBackendId("beyonddimensions");

    @Test
    void nanDishDoesNotHideMaterialsBeforeOrAfterItAndKeepsOriginalNbt() throws Exception {
        // The optional mod is absent in headless tests; reproduce its dish NBT on vanilla bread.
        ItemStack bread = dish(FloatTag.valueOf(Float.NaN));
        bread.getOrCreateTag().putInt("Damage", 0);
        String originalNbt = bread.save(new CompoundTag()).toString();
        ItemStack copper = new ItemStack(Items.COPPER_INGOT);
        ItemStack quartz = new ItemStack(Items.QUARTZ);
        List<ItemStack> skipped = new ArrayList<>();

        for (int attempt = 0; attempt < 2; attempt++) {
            var snapshot = BeyondDimensionsSession.readItemSnapshot(BACKEND, List.of(
                    entry(copper, 18), entry(bread, 1), entry(quartz, 8), entry(copper, 2)),
                    (stack, failure) -> {
                        assertTrue(failure.getMessage().contains("NaN"));
                        skipped.add(stack);
                    });

            assertEquals(2, snapshot.items().size());
            assertEquals(20, snapshot.countExact(BeyondDimensionsItemKeys.fromStack(BACKEND, copper)));
            assertEquals(8, snapshot.countExact(BeyondDimensionsItemKeys.fromStack(BACKEND, quartz)));
            assertEquals(originalNbt, bread.save(new CompoundTag()).toString());
            assertEquals(1, bread.getCount());
            assertTrue(Float.isNaN(bread.getTag().getCompound("dish_attribute").getFloat("nutrition")));
        }
        assertEquals(2, skipped.size());
        assertTrue(skipped.stream().allMatch(stack -> stack.is(Items.BREAD)));
    }

    @Test
    void skipsOnlyInvalidVariantAndRetainsFiniteDishNbtAndLongAmounts() throws Exception {
        ItemStack invalid = dish(DoubleTag.valueOf(Double.NaN));
        ItemStack valid = dish(FloatTag.valueOf(4.5f));
        long amount = (long) Integer.MAX_VALUE + 10;
        List<ItemStack> skipped = new ArrayList<>();

        var snapshot = BeyondDimensionsSession.readItemSnapshot(BACKEND,
                List.of(entry(invalid, 2), entry(valid, amount)), (stack, failure) -> skipped.add(stack));

        assertEquals(1, skipped.size());
        assertEquals(1, snapshot.items().size());
        assertEquals(amount, snapshot.countExact(BeyondDimensionsItemKeys.fromStack(BACKEND, valid)));
        assertEquals(valid.getTag(), snapshot.items().get(0).stack().getTag());
        assertThrows(IllegalArgumentException.class, () -> StorageItemKey.fromItemStack(BACKEND, invalid));
    }

    @Test
    void allUnsupportedItemsYieldNoUsableMaterialsWithoutChangingNativeEntries() throws Exception {
        ItemStack bread = dish(FloatTag.valueOf(Float.NaN));
        List<ItemStack> skipped = new ArrayList<>();
        var snapshot = BeyondDimensionsSession.readItemSnapshot(BACKEND,
                List.of(entry(bread, 64)), (stack, failure) -> skipped.add(stack));

        assertTrue(snapshot.items().isEmpty());
        assertEquals(1, skipped.size());
        assertTrue(Float.isNaN(bread.getTag().getCompound("dish_attribute").getFloat("nutrition")));
    }

    @Test
    void nonItemEmptyAndNonPositiveEntriesAreNotIdentityFailures() throws Exception {
        var snapshot = BeyondDimensionsSession.readItemSnapshot(BACKEND, List.of(
                new NativeEntry(new NonItemKey(), 1000), entry(ItemStack.EMPTY, 1),
                entry(dish(FloatTag.valueOf(Float.NaN)), 0), entry(new ItemStack(Items.IRON_INGOT), -1)),
                (stack, failure) -> fail("entries without usable items must not be converted"));

        assertTrue(snapshot.items().isEmpty());
    }

    @Test
    void backendReadFailuresAreNotSilentlyTurnedIntoPartialSuccess() {
        assertThrows(IllegalArgumentException.class, () -> BeyondDimensionsSession.readItemSnapshot(
                BACKEND, new Object(), (stack, failure) -> fail("not an item conversion error")));
        assertThrows(ReflectiveOperationException.class, () -> BeyondDimensionsSession.readItemSnapshot(
                BACKEND, List.of(entry(new ItemStack(Items.COPPER_INGOT), 18), new Object()),
                (stack, failure) -> fail("not an item conversion error")));
    }

    private static ItemStack dish(Tag nutrition) {
        ItemStack stack = new ItemStack(Items.BREAD);
        CompoundTag attributes = new CompoundTag();
        attributes.put("nutrition", nutrition);
        stack.getOrCreateTag().put("dish_attribute", attributes);
        return stack;
    }

    @Test
    void extractionFilterSkipsUnrelatedIdentitiesButRetainsRelevantInvalidItemDiagnostics() throws Exception {
        ItemStack invalid = dish(FloatTag.valueOf(Float.NaN));
        var entries = List.of(entry(invalid, 1), entry(new ItemStack(Items.IRON_INGOT), 20));
        List<ItemStack> skipped = new ArrayList<>();
        var filtered = BeyondDimensionsSession.readItemSnapshot(BACKEND, entries,
                Set.of(Items.IRON_INGOT), (stack, failure) -> skipped.add(stack));
        assertEquals(1, filtered.items().size());
        assertTrue(skipped.isEmpty(), "unrelated NBT must not be serialized");

        var relevant = BeyondDimensionsSession.readItemSnapshot(BACKEND, entries,
                Set.of(Items.BREAD), (stack, failure) -> skipped.add(stack));
        assertTrue(relevant.items().isEmpty());
        assertEquals(1, skipped.size());
        assertTrue(Float.isNaN(invalid.getTag().getCompound("dish_attribute").getFloat("nutrition")));
    }

    @Test
    void filteredMatchingEqualsFullSnapshotForOrdinaryStrictAndPartialIngredients() throws Exception {
        ItemStack first = new ItemStack(Items.IRON_INGOT);
        first.getOrCreateTag().putString("variant", "first");
        ItemStack second = first.copy();
        second.getOrCreateTag().putString("variant", "second");
        ItemStack gold = new ItemStack(Items.GOLD_INGOT);
        var entries = List.of(entry(first, (long) Integer.MAX_VALUE + 10),
                entry(new ItemStack(Items.STICK), 64), entry(gold, 3), entry(second, 5));
        var full = BeyondDimensionsSession.readItemSnapshot(BACKEND, entries,
                (stack, failure) -> fail(failure));
        var ingredients = List.of(Ingredient.of(Items.GOLD_INGOT, Items.IRON_INGOT),
                net.minecraftforge.common.crafting.StrictNBTIngredient.of(first),
                net.minecraftforge.common.crafting.PartialNBTIngredient.of(Items.IRON_INGOT, first.getTag()));
        for (Ingredient ingredient : ingredients) {
            var filtered = BeyondDimensionsSession.readItemSnapshot(BACKEND, entries,
                    BeyondDimensionsSession.extractionItemTypes(ingredient), (stack, failure) -> fail(failure));
            var expected = full.match(ingredient).items();
            var actual = filtered.match(ingredient).items();
            assertEquals(expected.stream().map(item -> item.key()).toList(),
                    actual.stream().map(item -> item.key()).toList());
            assertEquals(expected.stream().map(item -> item.amount()).toList(),
                    actual.stream().map(item -> item.amount()).toList());
        }
        assertEquals("first", first.getTag().getString("variant"));
    }

    @Test
    void customIngredientDoesNotRestrictExtractionToDisplayItems() throws Exception {
        Ingredient custom = new Ingredient(Stream.of(new Ingredient.ItemValue(new ItemStack(Items.IRON_INGOT)))) {
            @Override public boolean test(ItemStack stack) { return stack.is(Items.GOLD_INGOT); }
        };
        assertNull(BeyondDimensionsSession.extractionItemTypes(custom));
        var snapshot = BeyondDimensionsSession.readItemSnapshot(BACKEND,
                List.of(entry(new ItemStack(Items.IRON_INGOT), 1), entry(new ItemStack(Items.GOLD_INGOT), 2)),
                BeyondDimensionsSession.extractionItemTypes(custom), (stack, failure) -> fail(failure));
        assertEquals(List.of(Items.GOLD_INGOT), snapshot.match(custom).items().stream()
                .map(item -> item.stack().getItem()).toList());
        assertEquals(Set.of(), BeyondDimensionsSession.extractionItemTypes(Ingredient.EMPTY));
    }

    @Test
    void filteredNativeStackIsCopiedOnlyWhenSelected() throws Exception {
        ItemStack source = dish(FloatTag.valueOf(4.5f));
        var key = new NativeItemKey(source);
        assertSame(ItemStack.EMPTY, BeyondDimensionsReflection.keyStack(key, Set.of(Items.IRON_INGOT)));
        ItemStack selected = BeyondDimensionsReflection.keyStack(key, Set.of(Items.BREAD));
        assertNotSame(source, selected);
        selected.getOrCreateTag().putString("changed", "yes");
        assertFalse(source.getTag().contains("changed"));
    }

    private static NativeEntry entry(ItemStack stack, long amount) {
        return new NativeEntry(new NativeItemKey(stack), amount);
    }

    public record NativeEntry(Object key, long amount) {}

    public record NativeItemKey(ItemStack stack) {
        public Class<?> getStackClass() { return ItemStack.class; }
        public ItemStack getReadOnlyStack() { return stack; }
    }

    public static final class NonItemKey {
        public Class<?> getStackClass() { return String.class; }
    }
}
