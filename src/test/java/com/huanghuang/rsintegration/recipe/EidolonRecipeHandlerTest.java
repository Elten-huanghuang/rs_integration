package com.huanghuang.rsintegration.recipe;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EidolonRecipeHandlerTest extends BootstrapTest {

    @Test
    void ritualReservesReagentPedestalAndFocusItems() {
        var inputs = EidolonRecipeHandler.ritualNetworkIngredients(
                Ingredient.of(Items.BLAZE_POWDER),
                List.of(Ingredient.of(Items.IRON_INGOT), Ingredient.of(Items.IRON_INGOT)),
                List.of(Ingredient.of(Items.DIAMOND)));

        assertEquals(4, inputs.size());
        assertEquals(1, inputs.get(0).count());
        assertTrue(inputs.get(0).ingredient().test(new ItemStack(Items.BLAZE_POWDER)));
        assertTrue(inputs.get(1).ingredient().test(new ItemStack(Items.IRON_INGOT)));
        assertTrue(inputs.get(2).ingredient().test(new ItemStack(Items.IRON_INGOT)));
        assertTrue(inputs.get(3).ingredient().test(new ItemStack(Items.DIAMOND)));
    }
}
