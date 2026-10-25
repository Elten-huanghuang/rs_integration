package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.crafting.CraftingResolver.StackKey;
import com.huanghuang.rsintegration.crafting.DirectMaterialAllocator;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.ScaledIngredientSpec;
import com.huanghuang.rsintegration.crafting.consumption.InputConsumption;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScaledIngredientPlanTest extends BootstrapTest {
    @Test
    void scaledDemandKeepsAggregateAboveIntegerMaximum() {
        long executions = (long) Integer.MAX_VALUE + 5L;

        List<ScaledIngredientSpec> scaled = CraftIngredientPlanMath.scaleIngredientSpecs(
                List.of(new IngredientSpec(Ingredient.of(Items.DIAMOND), 2)), executions);

        assertEquals(2L * executions, scaled.get(0).count());
    }

    @Test
    void directAllocationUsesTheLongAggregateWithoutTruncation() {
        long amount = (long) Integer.MAX_VALUE + 5L;
        StackKey diamond = new StackKey(Items.DIAMOND, null);
        ScaledIngredientSpec demand = new ScaledIngredientSpec(
                Ingredient.of(Items.DIAMOND), amount, InputConsumption.consumed());

        DirectMaterialAllocator.Result result =
                DirectMaterialAllocator.allocateScaled(List.of(demand), Map.of(diamond, amount));

        assertTrue(result.feasible());
        assertEquals(amount, result.allocations().get(0).count());
    }
}
