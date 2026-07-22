package com.huanghuang.rsintegration.mods.vanilla.brewing;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.item.alchemy.PotionUtils;
import com.huanghuang.rsintegration.crafting.IngredientMatcher;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VanillaBrewingRecipeDefinitionTest extends BootstrapTest {
    @Test
    void oneOperationAdvertisesThreeExactPotionBottles() {
        ItemStack input = PotionUtils.setPotion(new ItemStack(Items.POTION), Potions.AWKWARD);
        ItemStack output = PotionUtils.setPotion(new ItemStack(Items.POTION), Potions.HEALING);
        VanillaBrewingRecipeDefinition recipe = new VanillaBrewingRecipeDefinition(
                new ResourceLocation("rs_integration", "test/brewing"),
                input, new ItemStack(Items.GLISTERING_MELON_SLICE), output);

        assertEquals(3, recipe.output().getCount());
        assertTrue(ItemStack.isSameItemSameTags(output, recipe.outputUnit()));
        assertEquals(3, recipe.getIngredients().size());
        assertTrue(recipe.getIngredients().get(0).test(input));
        assertFalse(recipe.getIngredients().get(0).test(
                PotionUtils.setPotion(new ItemStack(Items.POTION), Potions.WATER)));
    }

    @Test
    void thirstPurityTagDoesNotBreakWaterBottleMatching() {
        ItemStack expected = PotionUtils.setPotion(new ItemStack(Items.POTION), Potions.WATER);
        ItemStack actual = expected.copy();
        actual.getOrCreateTag().putInt("Purity", 2);
        assertTrue(IngredientMatcher.matchesWaterBottleIgnoringPurity(expected, actual));
        ItemStack healing = PotionUtils.setPotion(new ItemStack(Items.POTION), Potions.HEALING);
        assertFalse(IngredientMatcher.matchesWaterBottleIgnoringPurity(healing, actual));
    }
}
