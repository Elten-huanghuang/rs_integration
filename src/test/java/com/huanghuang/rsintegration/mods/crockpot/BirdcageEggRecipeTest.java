package com.huanghuang.rsintegration.mods.crockpot;

import com.huanghuang.rsintegration.recipe.ParrotFeedingRecipeHandler;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BirdcageEggRecipeTest extends BootstrapTest {
    @Test
    void syntheticEggRecipeKeepsMeatInputButIsNotARecursiveEggProducer() {
        BirdcageEggRecipe recipe = new BirdcageEggRecipe(BirdcageEggCatalog.MEAT_EGG_ID,
                Ingredient.of(Items.BEEF), false, new ItemStack(Items.EGG));
        ParrotFeedingRecipeHandler handler = new ParrotFeedingRecipeHandler();

        assertEquals(BirdcageEggCatalog.MEAT_EGG_ID, recipe.getId());
        assertTrue(recipe.matches(new SimpleContainer(new ItemStack(Items.BEEF)), null));
        assertFalse(recipe.matches(new SimpleContainer(new ItemStack(Items.WHEAT)), null));
        assertTrue(handler.canHandle(recipe));
        assertFalse(handler.indexPrimaryOutput(recipe));
        assertEquals(Items.EGG, handler.getResultItem(recipe, RegistryAccess.EMPTY).getItem());
        assertEquals(Items.BEEF, handler.getIngredients(recipe).get(0).ingredient().getItems()[0].getItem());
    }

    @Test
    void birdcageExposesDelayedEggAsWorldCaptureOutput() throws Exception {
        BirdcageBatchDelegate delegate = new BirdcageBatchDelegate();
        Field expectedOutput = BirdcageBatchDelegate.class.getDeclaredField("expectedOutput");
        expectedOutput.setAccessible(true);
        expectedOutput.set(delegate, new ItemStack(Items.EGG));

        assertEquals(Items.EGG, delegate.getExpectedOutput().getItem());
    }
}
