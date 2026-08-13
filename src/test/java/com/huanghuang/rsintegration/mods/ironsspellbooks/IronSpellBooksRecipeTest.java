package com.huanghuang.rsintegration.mods.ironsspellbooks;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class IronSpellBooksRecipeTest extends BootstrapTest {
    @Test
    void retainsExplicitScrollLevelForExecutionValidation() {
        IronSpellBooksRecipe recipe = new IronSpellBooksRecipe(
                new ResourceLocation("test", "scroll"),
                IronSpellBooksRecipe.Machine.SCROLL_FORGE,
                List.of(new ItemStack(Items.PAPER)),
                List.of(Ingredient.of(Items.PAPER)),
                new ItemStack(Items.PAPER), "test:spell", 2);

        assertEquals(2, recipe.spellLevel());
    }
}
