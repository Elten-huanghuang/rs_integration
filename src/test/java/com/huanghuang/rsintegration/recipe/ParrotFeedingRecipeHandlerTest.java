package com.huanghuang.rsintegration.recipe;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ParrotFeedingRecipeHandlerTest extends BootstrapTest {
    @Test
    void usesTheGuaranteedMinimumWhenRangedParrotOutputCannotBeEmpty() throws ReflectiveOperationException {
        ItemStack result = ParrotFeedingRecipeHandler.rangedPlanningResult(new FakeRangedItem(Items.WHEAT_SEEDS, 1, 3));

        assertEquals(Items.WHEAT_SEEDS, result.getItem());
        assertEquals(1, result.getCount());
    }

    @Test
    void keepsPossibleOutputVisibleForZeroToOneParrotFeed() throws ReflectiveOperationException {
        ItemStack result = ParrotFeedingRecipeHandler.rangedPlanningResult(new FakeRangedItem(Items.SLIME_BALL, 0, 1));

        assertEquals(Items.SLIME_BALL, result.getItem());
        assertEquals(1, result.getCount());
    }

    private static final class FakeRangedItem {
        public final Item item;
        public final int min;
        public final int max;

        private FakeRangedItem(Item item, int min, int max) {
            this.item = item;
            this.min = min;
            this.max = max;
        }
    }
}
