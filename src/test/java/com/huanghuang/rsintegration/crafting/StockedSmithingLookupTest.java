package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.SmithingTransformRecipe;
import net.minecraftforge.common.crafting.PartialNBTIngredient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class StockedSmithingLookupTest extends BootstrapTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void woodenAndStoneSeedsKeepFullStateAndMatchFullScan(boolean startFromStone) throws Exception {
        Item[] chain = {Items.WOODEN_SWORD, Items.STONE_SWORD, Items.IRON_SWORD,
                Items.DIAMOND_SWORD, Items.NETHERITE_SWORD};
        Map<Item, List<RecipeIndex.Entry>> index = new LinkedHashMap<>();
        SmithingTransformRecipe target = null;
        for (int position = 1; position < chain.length; position++) {
            target = recipe("chain_" + position, Ingredient.of(chain[position - 1]), chain[position]);
            index.put(chain[position], List.of(new RecipeIndex.Entry(target, ModType.GENERIC,
                    new ResourceLocation("minecraft:smithing"))));
        }
        var stock = new CountingStock();
        for (int variant = 0; variant < 2000; variant++) {
            stock.put(new CraftingResolver.StackKey(Items.COBBLESTONE, "{variant:" + variant + "}"), 64);
        }
        String tag = "{Unbreakable:1b,RepairCost:8,Damage:0,itemModifier:\"celestial_forge:vicious\"}";
        var seed = new CraftingResolver.StackKey(chain[startFromStone ? 1 : 0], tag);
        stock.put(seed, 1);
        Ingredient demand = PartialNBTIngredient.of(Items.NETHERITE_SWORD,
                TagParser.parseTag("{Unbreakable:1}"));
        ItemStack reference = resolve(target, demand, new InventoryCandidateLookup(stock, false), index);
        int fullScanChecks = stock.countReads;
        stock.countReads = 0;
        ItemStack optimized = resolve(target, demand, new InventoryCandidateLookup(stock), index);

        assertTrue(optimized.is(Items.NETHERITE_SWORD));
        assertTrue(ItemStack.isSameItemSameTags(reference, optimized));
        assertEquals(TagParser.parseTag(tag), optimized.getTag());
        assertEquals(1, optimized.getCount());
        assertEquals(1, stock.get(seed));
        assertEquals((startFromStone ? 3 : 4) * 2001, fullScanChecks);
        assertEquals(1, stock.countReads);
    }

    @Test
    void damagedSeedIsRejectedByBothPaths() throws Exception {
        var target = recipe("damaged", Ingredient.of(Items.WOODEN_SWORD), Items.STONE_SWORD);
        var demand = PartialNBTIngredient.of(Items.STONE_SWORD, TagParser.parseTag("{Unbreakable:1}"));
        var stock = Map.of(new CraftingResolver.StackKey(Items.WOODEN_SWORD,
                "{Unbreakable:1b,Damage:7}"), 1);
        for (boolean indexed : List.of(false, true)) {
            assertTrue(resolve(target, demand, new InventoryCandidateLookup(stock, indexed), Map.of()).isEmpty());
        }
    }

    @Test
    void customBaseWithHiddenAcceptedItemStillFindsStock() {
        Ingredient custom = new Ingredient(Stream.of(new Ingredient.ItemValue(new ItemStack(Items.STICK)))) {
            @Override
            public boolean test(ItemStack stack) {
                return stack.is(Items.WOODEN_SWORD);
            }
        };
        var target = recipe("custom_base", custom, Items.STONE_SWORD);
        var stock = Map.of(new CraftingResolver.StackKey(Items.WOODEN_SWORD, "{Unbreakable:1b}"), 1);
        assertTrue(resolve(target, Ingredient.of(Items.STONE_SWORD),
                new InventoryCandidateLookup(stock), Map.of()).is(Items.STONE_SWORD));
    }

    @Test
    void recipeSubclassWithOverriddenBasePredicateUsesFullStock() {
        var target = new SmithingTransformRecipe(new ResourceLocation("test:custom_recipe"),
                Ingredient.of(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE), Ingredient.of(Items.STICK),
                Ingredient.of(Items.IRON_INGOT), new ItemStack(Items.STONE_SWORD)) {
            @Override
            public boolean isBaseIngredient(ItemStack stack) {
                return stack.is(Items.WOODEN_SWORD);
            }
        };
        var stock = Map.of(new CraftingResolver.StackKey(Items.WOODEN_SWORD, "{Unbreakable:1b}"), 1);
        assertTrue(resolve(target, Ingredient.of(Items.STONE_SWORD),
                new InventoryCandidateLookup(stock), Map.of()).is(Items.STONE_SWORD));
    }

    @Test
    void multiBaseRecipeSelectsSamePhysicalVariantInInventoryOrder() {
        var target = recipe("ordered", Ingredient.of(Items.WOODEN_SWORD, Items.STONE_SWORD), Items.IRON_SWORD);
        var stock = new LinkedHashMap<CraftingResolver.StackKey, Integer>();
        stock.put(new CraftingResolver.StackKey(Items.STONE_SWORD, "{variant:2}"), 1);
        stock.put(new CraftingResolver.StackKey(Items.WOODEN_SWORD, "{variant:1}"), 1);
        for (boolean indexed : List.of(false, true)) {
            var output = resolve(target, Ingredient.of(Items.IRON_SWORD),
                    new InventoryCandidateLookup(stock, indexed), Map.of());
            assertEquals(2, output.getTag().getInt("variant"));
        }
    }

    @Test
    void failedOrCancelledQueryDoesNotPoisonNextQuery() {
        var target = recipe("cancel", Ingredient.of(Items.WOODEN_SWORD), Items.STONE_SWORD);
        var stock = Map.of(new CraftingResolver.StackKey(Items.WOODEN_SWORD, null), 1);
        var cancelled = new AtomicBoolean(true);
        assertTrue(CandidateEngine.findStockedSmithingOutput(target, stack -> true, stock,
                Map.of(), RegistryAccess.EMPTY, new HashSet<>(), cancelled::get).isEmpty());
        assertThrows(IllegalStateException.class, () -> CandidateEngine.findStockedSmithingOutput(
                target, stack -> { throw new IllegalStateException("probe failed"); }, stock,
                Map.of(), RegistryAccess.EMPTY, new HashSet<>(), () -> false));
        assertTrue(resolve(target, Ingredient.of(Items.STONE_SWORD),
                new InventoryCandidateLookup(stock), Map.of()).is(Items.STONE_SWORD));
    }

    private static ItemStack resolve(SmithingTransformRecipe recipe, Ingredient demand,
                                     InventoryCandidateLookup stock, Map<Item, List<RecipeIndex.Entry>> index) {
        return CandidateEngine.findStockedSmithingOutput(recipe, stack -> IngredientMatcher.test(demand, stack),
                stock, index, RegistryAccess.EMPTY, new HashSet<>(), () -> false);
    }

    private static SmithingTransformRecipe recipe(String name, Ingredient base, Item output) {
        return new SmithingTransformRecipe(new ResourceLocation("test", name),
                Ingredient.of(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE), base,
                Ingredient.of(Items.IRON_INGOT), new ItemStack(output));
    }

    private static final class CountingStock extends LinkedHashMap<CraftingResolver.StackKey, Integer> {
        private int countReads;

        @Override
        public Integer getOrDefault(Object key, Integer defaultValue) {
            countReads++;
            return super.getOrDefault(key, defaultValue);
        }
    }
}
