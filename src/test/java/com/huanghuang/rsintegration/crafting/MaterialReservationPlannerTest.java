package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.crafting.consumption.InputConsumption;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MaterialReservationPlannerTest extends BootstrapTest {
    @Test
    void reusableDurabilitySpecScalesByActualToolCapacity() {
        IngredientSpec tool = new IngredientSpec(
                Ingredient.of(Items.IRON_SWORD), 1, InputConsumption.durability(1L));

        List<IngredientSpec> scaled = MaterialReservationPlanner.scaleGraphSpecsForExecutions(
                List.of(tool),
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
                64);

        assertEquals(1, scaled.get(0).count());
    }

    @Test
    void preservesEmptyPhysicalSlotsWhileScalingRealInputs() {
        IngredientSpec emptySlot = new IngredientSpec(Ingredient.EMPTY, 0);
        IngredientSpec iron = new IngredientSpec(Ingredient.of(Items.IRON_BLOCK), 1);

        List<IngredientSpec> scaled = MaterialReservationPlanner.scaleGraphSpecsForExecutions(
                List.of(emptySlot, iron, emptySlot),
                3);

        assertEquals(3, scaled.size());
        assertTrue(scaled.get(0).isEmpty());
        assertEquals(3, scaled.get(1).count());
        assertTrue(scaled.get(2).isEmpty());
    }

}
