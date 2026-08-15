package com.huanghuang.rsintegration.recipe;

import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnchantalCoolerRecipeHandlerTest extends BootstrapTest {

    @Test
    void appendsDeclaredContainerAfterOrdinaryInputs() {
        List<IngredientSpec> inputs = List.of(
                new IngredientSpec(Ingredient.of(Items.APPLE), 1),
                new IngredientSpec(Ingredient.of(Items.CARROT), 1));

        List<IngredientSpec> specs = EnchantalCoolerRecipeHandler.appendContainerSpec(
                inputs, new ItemStack(Items.GLASS_BOTTLE));

        assertEquals(3, specs.size());
        assertTrue(specs.get(0).ingredient().test(new ItemStack(Items.APPLE)));
        assertTrue(specs.get(1).ingredient().test(new ItemStack(Items.CARROT)));
        assertTrue(specs.get(2).ingredient().test(new ItemStack(Items.GLASS_BOTTLE)));
        assertEquals(1, specs.get(2).count());
    }

    @Test
    void emptyContainerDoesNotAddARequirement() {
        List<IngredientSpec> inputs = List.of(
                new IngredientSpec(Ingredient.of(Items.APPLE), 1));

        assertEquals(inputs, EnchantalCoolerRecipeHandler.appendContainerSpec(
                inputs, ItemStack.EMPTY));
    }
}
