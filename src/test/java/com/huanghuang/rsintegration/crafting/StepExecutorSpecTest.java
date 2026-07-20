package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.crafting.graph.DemandRole;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.NonNullList;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.ShapedRecipe;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StepExecutorSpecTest extends BootstrapTest {

    @Test
    void coalescesRepeatedSlotsWithoutFlatteningCatalystRole() {
        Ingredient nuggets = Ingredient.of(Items.IRON_NUGGET);
        Ingredient block = Ingredient.of(Items.IRON_BLOCK);

        List<IngredientSpec> merged = StepExecutor.coalesceSpecsForGraph(List.of(
                new IngredientSpec(nuggets, 1),
                new IngredientSpec(nuggets, 1),
                new IngredientSpec(nuggets, 1),
                new IngredientSpec(block, 1, DemandRole.CATALYST),
                new IngredientSpec(block, 1, DemandRole.CATALYST),
                new IngredientSpec(block, 1, DemandRole.CONSUMED)));

        assertEquals(3, merged.size());
        assertEquals(3, merged.get(0).count());
        assertEquals(DemandRole.CONSUMED, merged.get(0).role());
        assertEquals(2, merged.get(1).count());
        assertEquals(DemandRole.CATALYST, merged.get(1).role());
        assertEquals(1, merged.get(2).count());
        assertEquals(DemandRole.CONSUMED, merged.get(2).role());
        assertEquals(12, CraftPacketUtils.requiredCount(merged.get(0), 4));
        assertEquals(2, CraftPacketUtils.requiredCount(merged.get(1), 4));
    }

    @Test
    void mapsCompactShapedRecipesIntoThreeByThreeRows() {
        ShapedRecipe recipe = new ShapedRecipe(
                new ResourceLocation("test", "two_by_three"), "", CraftingBookCategory.MISC,
                2, 3, NonNullList.withSize(6, Ingredient.of(Items.GLASS)),
                new ItemStack(Items.GLASS_BOTTLE));

        assertEquals(List.of(0, 1, 3, 4, 6, 7),
                java.util.stream.IntStream.range(0, 6)
                        .map(i -> CraftPacketUtils.craftingGridSlot(recipe, i))
                        .boxed().toList());
    }

    @Test
    void rootResolutionCoalescesSlotsAndKeepsCatalystsUnscaled() {
        Ingredient nuggets = Ingredient.of(Items.IRON_NUGGET);
        Ingredient block = Ingredient.of(Items.IRON_BLOCK);
        List<IngredientSpec> roots = new java.util.ArrayList<>();
        for (int i = 0; i < 9; i++) roots.add(new IngredientSpec(nuggets, 1));
        roots.add(new IngredientSpec(block, 1, DemandRole.CATALYST));

        List<IngredientSpec> merged = CraftingResolver.coalesceRootSpecs(roots);

        assertEquals(2, merged.size());
        assertEquals(9, merged.get(0).count());
        assertEquals(DemandRole.CONSUMED, merged.get(0).role());
        assertEquals(1, CraftPacketUtils.requiredCount(merged.get(1), 64));
        assertEquals(DemandRole.CATALYST, merged.get(1).role());
    }
}
