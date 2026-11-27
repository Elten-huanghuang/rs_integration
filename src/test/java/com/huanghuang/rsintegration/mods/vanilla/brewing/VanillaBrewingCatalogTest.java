package com.huanghuang.rsintegration.mods.vanilla.brewing;

import com.huanghuang.rsintegration.crafting.RecipeIndex;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionUtils;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraftforge.common.brewing.IBrewingRecipe;
import net.minecraftforge.common.brewing.VanillaBrewingRecipe;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VanillaBrewingCatalogTest extends BootstrapTest {
    @Test
    void skipsRepeatVanillaProbeButStillIndexesModdedInputsAndForgeSubclasses() {
        var water = PotionUtils.setPotion(new ItemStack(Items.POTION), Potions.WATER);
        var wart = new ItemStack(Items.NETHER_WART);
        var apple = new ItemStack(Items.APPLE);
        var calls = new AtomicInteger();
        IBrewingRecipe custom = new VanillaBrewingRecipe() {
            @Override public boolean isInput(ItemStack stack) { return stack.is(Items.APPLE); }
            @Override public boolean isIngredient(ItemStack stack) {
                calls.incrementAndGet();
                return stack.is(Items.NETHER_WART);
            }
            @Override public ItemStack getOutput(ItemStack input, ItemStack reagent) {
                return new ItemStack(Items.DIAMOND);
            }
        };
        Map<Item, List<RecipeIndex.Entry>> index = new HashMap<>();
        var projected = new HashMap<ImmutableRecipeGraph.MaterialRef, List<ImmutableRecipeGraph.RecipeNode>>();
        var build = new VanillaBrewingCatalog.IncrementalIndex(index, new HashSet<>(),
                List.of(water, apple), List.of(wart), List.of(new VanillaBrewingRecipe(), custom), projected);
        while (!build.advance(() -> true)) { }

        assertEquals(1, calls.get());
        assertTrue(index.get(Items.POTION).stream().anyMatch(entry ->
                entry.recipe() instanceof VanillaBrewingRecipeDefinition recipe
                        && ItemStack.isSameItemSameTags(water, recipe.input())
                        && PotionUtils.getPotion(recipe.outputUnit()) == Potions.AWKWARD));
        assertTrue(index.get(Items.DIAMOND).stream().anyMatch(entry ->
                entry.recipe() instanceof VanillaBrewingRecipeDefinition recipe
                        && ItemStack.isSameItemSameTags(apple, recipe.input())));
        var graph = new ImmutableRecipeGraph(projected);
        assertTrue(index.get(Items.POTION).stream().anyMatch(entry ->
                graph.recipesById().containsKey(entry.recipe().getId())));
    }

    @Test
    void neverScansReagentsWhenNoInputsMatch() {
        IBrewingRecipe noInputs = new TestBrewingRecipe() {
            @Override public boolean isInput(ItemStack stack) { return false; }
            @Override public boolean isIngredient(ItemStack stack) {
                throw new AssertionError("没有匹配的原料，不应扫描试剂");
            }
        };
        var build = new VanillaBrewingCatalog.IncrementalIndex(new HashMap<>(), new HashSet<>(),
                List.of(new ItemStack(Items.APPLE)), List.of(new ItemStack(Items.NETHER_WART)),
                List.of(noInputs), null);
        assertTrue(build.advance(() -> false));
    }

    @Test
    void brokenThirdPartyBrewingHooksDoNotAbortTheRemainingCatalog() {
        ItemStack input = new ItemStack(Items.POTION);
        ItemStack reagent = new ItemStack(Items.NETHER_WART);
        ItemStack output = new ItemStack(Items.SPLASH_POTION);

        IBrewingRecipe brokenInput = new TestBrewingRecipe() {
            @Override
            public boolean isInput(ItemStack ignored) {
                throw new NullPointerException("missing NBT");
            }
        };
        IBrewingRecipe brokenIngredient = new TestBrewingRecipe() {
            @Override
            public boolean isIngredient(ItemStack ignored) {
                throw new NullPointerException("missing NBT");
            }
        };
        IBrewingRecipe brokenOutput = new TestBrewingRecipe() {
            @Override
            public ItemStack getOutput(ItemStack ignoredInput, ItemStack ignoredReagent) {
                throw new NullPointerException("missing NBT");
            }
        };
        IBrewingRecipe valid = new TestBrewingRecipe() {
            @Override
            public ItemStack getOutput(ItemStack ignoredInput, ItemStack ignoredReagent) {
                return output.copy();
            }
        };

        Map<Item, List<RecipeIndex.Entry>> index = new HashMap<>();
        VanillaBrewingCatalog.IncrementalIndex build = new VanillaBrewingCatalog.IncrementalIndex(
                index, new HashSet<>(), List.of(input), List.of(reagent),
                List.of(brokenInput, brokenIngredient, brokenOutput, valid), null);
        assertFalse(build.advance(() -> true));
        while (!build.advance(() -> true)) { }

        assertTrue(index.containsKey(Items.SPLASH_POTION));
        assertFalse(index.getOrDefault(Items.SPLASH_POTION, List.of()).isEmpty());
    }

    private static class TestBrewingRecipe implements IBrewingRecipe {
        @Override
        public boolean isInput(ItemStack input) {
            return true;
        }

        @Override
        public boolean isIngredient(ItemStack ingredient) {
            return true;
        }

        @Override
        public ItemStack getOutput(ItemStack input, ItemStack ingredient) {
            return new ItemStack(Items.SPLASH_POTION);
        }
    }
}
