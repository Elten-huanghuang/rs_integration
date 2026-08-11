package com.huanghuang.rsintegration.recipe;

import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.graph.DemandRole;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.core.NonNullList;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GenericRecipeHandlerTest extends BootstrapTest {
    @Test
    void preservesCraftingRemainderSemantics() {
        ShapelessRecipe recipe = new ShapelessRecipe(
                new ResourceLocation("test", "generic_handler_remainder"), "",
                CraftingBookCategory.MISC, new ItemStack(Items.CLAY_BALL),
                NonNullList.of(Ingredient.EMPTY, Ingredient.of(Items.WATER_BUCKET)));

        IngredientSpec spec = new GenericRecipeHandler().getIngredients(recipe).stream()
                .filter(candidate -> !candidate.isEmpty()).findFirst().orElseThrow();

        assertEquals(DemandRole.CONTAINER_RETURNING, spec.role());
        assertEquals(65, CraftPacketUtils.requiredCount(spec, 65));
    }
}
