package com.huanghuang.rsintegration.crafting;

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

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CraftPacketUtilsCatalystTest extends BootstrapTest {

    @Test
    void unchangedForgeRemainderIsReusableCatalyst() {
        Ingredient ingredient = Ingredient.of(Items.SHEARS);
        DemandRole role = CraftPacketUtils.craftingDemandRole(
                ingredient, stack -> stack.copyWithCount(1));
        IngredientSpec spec = new IngredientSpec(ingredient, 1, role);

        assertEquals(DemandRole.CATALYST, spec.role());
        assertEquals(1, CraftPacketUtils.requiredCount(spec, 16));
        assertEquals(1, CraftPacketUtils.remainderExecutions(
                new ItemStack(Items.SHEARS), List.of(spec), 16));
    }

    @Test
    void replacementContainerStillScalesPerExecution() {
        ShapelessRecipe recipe = recipe("replacement_container", Ingredient.of(Items.WATER_BUCKET));

        IngredientSpec spec = CraftPacketUtils.extractCraftingIngredientSpecs(recipe).get(0);

        assertEquals(DemandRole.CONTAINER_RETURNING, spec.role());
        assertEquals(16, CraftPacketUtils.requiredCount(spec, 16));
        List<ItemStack> remainders = CraftPacketUtils.getRecipeRemainders(
                recipe, new ItemStack[]{new ItemStack(Items.WATER_BUCKET)});
        assertTrue(remainders.get(0).is(Items.BUCKET));
        assertEquals(16, CraftPacketUtils.remainderExecutions(remainders.get(0), List.of(spec), 16));
    }

    @Test
    void mixedTagCandidatesAreNotAssumedReusable() {
        Ingredient mixed = Ingredient.of(Items.SHEARS, Items.IRON_INGOT);

        assertEquals(DemandRole.CONTAINER_RETURNING,
                CraftPacketUtils.craftingDemandRole(mixed, stack ->
                        stack.is(Items.SHEARS) ? stack.copyWithCount(1) : ItemStack.EMPTY));
    }

    private static ShapelessRecipe recipe(String path, Ingredient ingredient) {
        return new ShapelessRecipe(new ResourceLocation("test", path), "",
                CraftingBookCategory.MISC,
                new ItemStack(Items.COPPER_INGOT),
                NonNullList.of(Ingredient.EMPTY, ingredient));
    }
}
