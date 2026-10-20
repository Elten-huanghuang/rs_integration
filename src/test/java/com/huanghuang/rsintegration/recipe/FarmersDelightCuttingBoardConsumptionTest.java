package com.huanghuang.rsintegration.recipe;

import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.graph.DemandRole;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FarmersDelightCuttingBoardConsumptionTest extends BootstrapTest {
    @Test
    void toolIsDurabilityStateAndScalesAcrossMultipleTools() {
        List<IngredientSpec> specs = FarmersDelightRecipeHandler.cuttingBoardIngredients(
                Ingredient.of(Items.CARROT), Ingredient.of(Items.IRON_SWORD));

        IngredientSpec tool = specs.get(1);
        assertEquals(DemandRole.DURABILITY, tool.role());
        assertEquals(2, CraftPacketUtils.requiredCount(tool, 300));
    }

    @Test
    void durabilityToolStaysARuntimeResourceInsteadOfARecursiveDemand() {
        List<IngredientSpec> specs = FarmersDelightRecipeHandler.cuttingBoardIngredients(
                Ingredient.of(Items.CARROT), Ingredient.of(Items.IRON_SWORD));

        List<IngredientSpec> recursive =
                FarmersDelightRecipeHandler.cuttingBoardGraphIngredients(specs);

        assertEquals(1, recursive.size());
        assertEquals(DemandRole.CONSUMED, recursive.get(0).role());
    }
}
