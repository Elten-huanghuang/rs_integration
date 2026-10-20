package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.crafting.batch.IBatchDelegate;
import com.huanghuang.rsintegration.crafting.consumption.InputConsumption;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MaterialReservationPlannerTest extends BootstrapTest {
    @Test
    void reusableDurabilitySpecScalesByActualToolCapacity() {
        IngredientSpec tool = new IngredientSpec(
                Ingredient.of(Items.IRON_SWORD), 1, InputConsumption.durability(1L));

        List<IngredientSpec> scaled = MaterialReservationPlanner.scaleGraphSpecsForExecutions(
                List.of(tool),
                List.of(IBatchDelegate.MaterialReservationScope.PER_WORKER_REUSABLE),
                300);

        assertEquals(2, scaled.get(0).count());
        assertEquals(InputConsumption.Kind.DURABILITY,
                scaled.get(0).consumption().kind());
    }

    @Test
    void reusableCatalystIsNotMultipliedByExecutions() {
        IngredientSpec catalyst = new IngredientSpec(
                Ingredient.of(Items.DIAMOND), 1, InputConsumption.catalyst());

        List<IngredientSpec> scaled = MaterialReservationPlanner.scaleGraphSpecsForExecutions(
                List.of(catalyst),
                List.of(IBatchDelegate.MaterialReservationScope.PER_WORKER_REUSABLE),
                64);

        assertEquals(1, scaled.get(0).count());
    }

}
