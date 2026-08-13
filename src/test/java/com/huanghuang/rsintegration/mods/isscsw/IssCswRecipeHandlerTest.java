package com.huanghuang.rsintegration.mods.isscsw;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import org.junit.jupiter.api.Test;

import javax.annotation.Nullable;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IssCswRecipeHandlerTest extends BootstrapTest {
    @Test
    void normalizesIngredientWhoseBaseValuesAreEmptyButDisplayStacksAreValid() {
        Ingredient broken = new DisplayOnlyIngredient(new ItemStack(Items.PAPER));

        assertTrue(broken.isEmpty());
        Ingredient normalized = IssCswRecipeHandler.normalizeIngredient(broken);

        assertFalse(normalized.isEmpty());
        assertTrue(normalized.test(new ItemStack(Items.PAPER)));
        assertFalse(normalized.test(new ItemStack(Items.DIAMOND)));
    }

    private static final class DisplayOnlyIngredient extends Ingredient {
        private final ItemStack display;

        private DisplayOnlyIngredient(ItemStack display) {
            super(Stream.empty());
            this.display = display;
        }

        @Override public ItemStack[] getItems() { return new ItemStack[]{display.copy()}; }

        @Override public boolean test(@Nullable ItemStack stack) {
            return stack != null && stack.is(display.getItem());
        }
    }
}
