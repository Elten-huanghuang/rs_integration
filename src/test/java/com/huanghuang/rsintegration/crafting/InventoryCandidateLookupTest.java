package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.nbt.TagParser;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraftforge.common.crafting.PartialNBTIngredient;
import net.minecraftforge.common.crafting.StrictNBTIngredient;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class InventoryCandidateLookupTest extends BootstrapTest {
    @Test
    void multiItemQueriesPreserveInventoryOrderAndDistinctNbtVariants() {
        var first = new CraftingResolver.StackKey(Items.GOLD_INGOT, "{variant:1}");
        var second = new CraftingResolver.StackKey(Items.IRON_INGOT, null);
        var third = new CraftingResolver.StackKey(Items.GOLD_INGOT, "{variant:2}");
        Map<CraftingResolver.StackKey, Integer> stock = new LinkedHashMap<>();
        stock.put(first, 1);
        stock.put(new CraftingResolver.StackKey(Items.STICK, null), 50);
        stock.put(second, 2);
        stock.put(third, 3);
        var lookup = new InventoryCandidateLookup(stock);

        assertEquals(List.of(first, second, third), new ArrayList<>(lookup.keysFor(
                Ingredient.of(Items.IRON_INGOT, Items.GOLD_INGOT, Items.GOLD_INGOT))));
        assertEquals(List.of(first, third), new ArrayList<>(lookup.keysFor(Ingredient.of(Items.GOLD_INGOT))));
    }

    @Test
    void nbtModesOnlyNarrowItemsAndLeaveStateCheckingToMatcher() throws Exception {
        ItemStack required = new ItemStack(Items.WOODEN_SWORD);
        required.setTag(TagParser.parseTag("{Unbreakable:1}"));
        var fresh = new CraftingResolver.StackKey(Items.WOODEN_SWORD, "{Unbreakable:1b,Damage:0}");
        var modified = new CraftingResolver.StackKey(Items.WOODEN_SWORD,
                "{Unbreakable:1b,Damage:0,RepairCost:8}");
        var damaged = new CraftingResolver.StackKey(Items.WOODEN_SWORD, "{Unbreakable:1b,Damage:9}");
        var stock = new LinkedHashMap<CraftingResolver.StackKey, Integer>();
        for (var key : List.of(fresh, modified, damaged)) stock.put(key, 1);
        stock.put(new CraftingResolver.StackKey(Items.STONE, null), 64);
        var lookup = new InventoryCandidateLookup(stock);
        for (Ingredient ingredient : List.of(Ingredient.of(required), StrictNBTIngredient.of(required),
                PartialNBTIngredient.of(Items.WOODEN_SWORD, required.getTag()))) {
            var expected = stock.keySet().stream().filter(key -> IngredientMatcher.test(ingredient, key)).toList();
            var actual = lookup.keysFor(ingredient).stream()
                    .filter(key -> IngredientMatcher.test(ingredient, key)).toList();
            assertEquals(expected, actual);
        }
        assertEquals(List.of(fresh, modified), lookup.keysFor(StrictNBTIngredient.of(required)).stream()
                .filter(key -> IngredientMatcher.test(StrictNBTIngredient.of(required), key)).toList());
    }

    @Test
    void customIngredientWithIncompleteTaglessDisplayUsesFullStock() {
        var iron = new CraftingResolver.StackKey(Items.IRON_INGOT, null);
        var gold = new CraftingResolver.StackKey(Items.GOLD_INGOT, null);
        var stock = Map.of(iron, 1, gold, 2);
        Ingredient custom = new Ingredient(Stream.of(new Ingredient.ItemValue(new ItemStack(Items.IRON_INGOT)))) {
            @Override
            public boolean test(ItemStack stack) {
                return stack.is(Items.GOLD_INGOT);
            }
        };
        assertFalse(IngredientMatcher.hasCompleteItemList(custom));
        assertEquals(stock.keySet(), new InventoryCandidateLookup(stock).keysFor(custom));
    }

    @Test
    void strictIngredientSubclassIsNotAssumedToHaveCompleteDisplay() {
        Ingredient custom = new StrictNBTIngredient(new ItemStack(Items.IRON_INGOT)) {
            @Override
            public boolean test(ItemStack stack) {
                return stack.is(Items.GOLD_INGOT);
            }
        };
        assertFalse(IngredientMatcher.hasCompleteItemList(custom));
        var stock = Map.of(new CraftingResolver.StackKey(Items.GOLD_INGOT, null), 1);
        assertEquals(stock.keySet(), new InventoryCandidateLookup(stock).keysFor(custom));
    }

    @Test
    void nextQuerySeesNewKeysAndQuantitiesWithoutCrossRequestCache() {
        var stock = new LinkedHashMap<CraftingResolver.StackKey, Integer>();
        var first = new CraftingResolver.StackKey(Items.IRON_INGOT, "{variant:1}");
        var second = new CraftingResolver.StackKey(Items.IRON_INGOT, "{variant:2}");
        stock.put(first, 1);
        assertEquals(List.of(first), new ArrayList<>(new InventoryCandidateLookup(stock)
                .keysFor(Ingredient.of(Items.IRON_INGOT))));
        stock.remove(first);
        stock.put(second, 7);
        var next = new InventoryCandidateLookup(stock);
        assertEquals(List.of(second), new ArrayList<>(next.keysFor(Ingredient.of(Items.IRON_INGOT))));
        assertEquals(7, next.count(second));
    }

    @Test
    void disabledIndexReturnsCompleteInventory() {
        var stock = Map.of(new CraftingResolver.StackKey(Items.GOLD_INGOT, null), 1);
        assertTrue(new InventoryCandidateLookup(stock).keysFor(Ingredient.of(Items.IRON_INGOT)).isEmpty());
        assertEquals(stock.keySet(), new InventoryCandidateLookup(stock, false)
                .keysFor(Ingredient.of(Items.IRON_INGOT)));
    }
}
