package com.huanghuang.rsintegration.mods.goety;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.world.item.EnchantedBookItem;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.EnchantmentInstance;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraftforge.common.crafting.StrictNBTIngredient;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GoetyDynamicRitualRecipeTest extends BootstrapTest {

    @Test
    void levelOneStartsFromPlainBook() {
        var output = GoetyDynamicRitualRecipe.buildOutput(Enchantments.UNBREAKING, 1);
        var input = GoetyDynamicRitualRecipe.buildInput(Enchantments.UNBREAKING, 1, output);

        assertTrue(input.is(Items.BOOK));
        assertEquals(1, GoetyDynamicRitualRecipe.inferTargetLevel(
                Enchantments.UNBREAKING, output));
    }

    @Test
    void levelThreeRequiresExactLevelTwoBook() {
        var output = GoetyDynamicRitualRecipe.buildOutput(Enchantments.UNBREAKING, 3);
        var input = GoetyDynamicRitualRecipe.buildInput(Enchantments.UNBREAKING, 3, output);

        assertTrue(input.is(Items.ENCHANTED_BOOK));
        assertEquals(2, EnchantmentHelper.getEnchantments(input)
                .getOrDefault(Enchantments.UNBREAKING, 0));
    }

    @Test
    void derivingInputPreservesExistingCompatibleEnchantments() {
        var output = EnchantedBookItem.createForEnchantment(
                new EnchantmentInstance(Enchantments.UNBREAKING, 3));
        EnchantedBookItem.addEnchantment(output,
                new EnchantmentInstance(Enchantments.MENDING, 1));

        var input = GoetyDynamicRitualRecipe.buildInput(
                Enchantments.UNBREAKING, 3, output);
        var enchantments = EnchantmentHelper.getEnchantments(input);

        assertEquals(2, enchantments.getOrDefault(Enchantments.UNBREAKING, 0));
        assertEquals(1, enchantments.getOrDefault(Enchantments.MENDING, 0));
    }

    @Test
    void rejectsOutputWithoutRecipesEnchantment() {
        var wrong = GoetyDynamicRitualRecipe.buildOutput(Enchantments.MENDING, 1);

        assertEquals(0, GoetyDynamicRitualRecipe.inferTargetLevel(
                Enchantments.UNBREAKING, wrong));
        assertTrue(GoetyDynamicRitualRecipe.buildInput(
                Enchantments.UNBREAKING, 2, wrong).isEmpty());
    }

    @Test
    void sharedRecipeMatchesTheExactDemandedLevel() {
        var levelThree = GoetyDynamicRitualRecipe.buildOutput(Enchantments.UNBREAKING, 3);

        var matched = GoetyDynamicRitualRecipe.matchingOutput(
                Enchantments.UNBREAKING, StrictNBTIngredient.of(levelThree));

        assertEquals(3, EnchantmentHelper.getEnchantments(matched)
                .getOrDefault(Enchantments.UNBREAKING, 0));
    }
}
