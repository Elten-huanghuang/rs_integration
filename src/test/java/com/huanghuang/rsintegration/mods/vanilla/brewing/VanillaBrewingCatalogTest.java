package com.huanghuang.rsintegration.mods.vanilla.brewing;

import com.huanghuang.rsintegration.crafting.RecipeIndex;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.common.brewing.IBrewingRecipe;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VanillaBrewingCatalogTest extends BootstrapTest {
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
                List.of(brokenInput, brokenIngredient, brokenOutput, valid));
        while (!build.advance(() -> false)) { }

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
