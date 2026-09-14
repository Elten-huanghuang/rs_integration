package com.huanghuang.rsintegration.recipe;

import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AvaritiaRecipeHandlerTest extends BootstrapTest {

    @Test
    void extremeSmithingAlternativesBecomeThreePositionalInputs() {
        Ingredient additions = Ingredient.of(
                Items.GOLDEN_CARROT, Items.DRAGON_EGG, Items.NETHERITE_INGOT);

        List<IngredientSpec> specs = AvaritiaRecipeHandler.getSmithingAdditionSpecs(additions);

        assertEquals(3, specs.size());
        assertTrue(specs.get(0).ingredient().test(new ItemStack(Items.GOLDEN_CARROT)));
        assertFalse(specs.get(0).ingredient().test(new ItemStack(Items.DRAGON_EGG)));
        assertTrue(specs.get(1).ingredient().test(new ItemStack(Items.DRAGON_EGG)));
        assertFalse(specs.get(1).ingredient().test(new ItemStack(Items.NETHERITE_INGOT)));
        assertTrue(specs.get(2).ingredient().test(new ItemStack(Items.NETHERITE_INGOT)));
        assertFalse(specs.get(2).ingredient().test(new ItemStack(Items.GOLDEN_CARROT)));
    }

    @Test
    void nonPositionalAdditionRemainsValidForEverySlot() {
        Ingredient additions = Ingredient.of(Items.DRAGON_EGG);

        List<IngredientSpec> specs = AvaritiaRecipeHandler.getSmithingAdditionSpecs(additions);

        assertEquals(3, specs.size());
        assertTrue(specs.stream().allMatch(
                spec -> spec.ingredient().test(new ItemStack(Items.DRAGON_EGG))));
    }
}
