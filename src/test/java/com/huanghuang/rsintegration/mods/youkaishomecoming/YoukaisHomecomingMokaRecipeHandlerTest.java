package com.huanghuang.rsintegration.mods.youkaishomecoming;

import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class YoukaisHomecomingMokaRecipeHandlerTest extends BootstrapTest {

    @Test
    void mokaContainerIsPartOfTheRecursiveMaterialPlan() {
        List<IngredientSpec> specs = YoukaisHomecomingRecipeHandler.appendMokaContainerSpec(
                List.of(new IngredientSpec(Ingredient.of(Items.COCOA_BEANS), 1)),
                new ItemStack(Items.GLASS_BOTTLE, 2));

        assertEquals(2, specs.size());
        assertEquals(2, specs.get(1).count());
        assertTrue(specs.get(1).ingredient().test(new ItemStack(Items.GLASS_BOTTLE)));
    }
}
