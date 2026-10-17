package com.huanghuang.rsintegration.recipe;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeepFryingRecipeHandlerTest extends BootstrapTest {

    @Test
    void readsTheRecipesLegacyNoArgResultMethod() {
        ItemStack result = DeepFryingRecipeHandler.readDirectResult(new LegacyResultRecipe());

        assertTrue(ItemStack.isSameItem(result, new ItemStack(Items.STONE)));
        assertEquals(3, result.getCount());
    }

    private static final class LegacyResultRecipe {
        public ItemStack getResultItem() {
            return new ItemStack(Items.STONE, 3);
        }
    }
}
