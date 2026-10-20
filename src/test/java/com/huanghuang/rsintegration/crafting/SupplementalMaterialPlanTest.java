package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.crafting.CraftingResolver.StackKey;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SupplementalMaterialPlanTest extends BootstrapTest {
    @Test
    void collectedTargetIsProtectedEvenInPreparationPlansWithoutADownstreamStep() {
        StackKey egg = StackKey.of(new ItemStack(Items.EGG), true);
        Map<StackKey, Long> available = Map.of(egg, 5L);
        ProductionTarget target = ProductionTarget.of(new ItemStack(Items.EGG), 3);
        Map<StackKey, Long> usable = SupplementalMaterialPlan.afterProtecting(available,
                List.of(new IngredientSpec(Ingredient.of(Items.EGG), 3)));
        SupplementalMaterialPlan.protectProduced(available, usable, target, 2);
        assertEquals(2, usable.get(egg));
        Map<StackKey, Long> preparation = SupplementalMaterialPlan.afterProtecting(available, List.of());
        SupplementalMaterialPlan.protectProduced(available, preparation, target, 2);
        assertEquals(3, preparation.get(egg));
    }

    @Test
    void retriesCannotConsumeTheInputsAlreadyNeededByDownstreamSteps() {
        StackKey iron = StackKey.of(new ItemStack(Items.IRON_INGOT), true);
        StackKey egg = StackKey.of(new ItemStack(Items.EGG), true);
        Map<StackKey, Long> original = Map.of(iron, 7L, egg, 3L);
        Map<StackKey, Long> remaining = SupplementalMaterialPlan.afterProtecting(original,
                List.of(new IngredientSpec(Ingredient.of(Items.IRON_INGOT), 5),
                        new IngredientSpec(Ingredient.of(Items.EGG), 4)));
        assertEquals(2, remaining.get(iron));
        assertEquals(0, remaining.get(egg));
        assertEquals(7, original.get(iron));
        assertTrue(SupplementalMaterialPlan.hasInputs(remaining,
                List.of(new IngredientSpec(Ingredient.of(Items.IRON_INGOT), 2))));
        assertFalse(SupplementalMaterialPlan.hasInputs(remaining,
                List.of(new IngredientSpec(Ingredient.of(Items.IRON_INGOT), 3))));
    }

    @Test
    void repeatedSlotsCannotReuseTheSameAvailableItem() {
        Map<StackKey, Long> available = Map.of(StackKey.of(new ItemStack(Items.EGG), true), 1L);
        IngredientSpec slot = new IngredientSpec(Ingredient.of(Items.EGG), 1);
        assertFalse(SupplementalMaterialPlan.hasInputs(available, List.of(slot, slot)));
        assertEquals(1, available.values().iterator().next());
    }

    @Test
    void oneMachineSlotCannotCombineDifferentVariants() {
        Map<StackKey, Long> available = Map.of(
                StackKey.of(new ItemStack(Items.RED_WOOL), true), 1L,
                StackKey.of(new ItemStack(Items.WHITE_WOOL), true), 1L);
        Ingredient wool = Ingredient.of(Items.RED_WOOL, Items.WHITE_WOOL);
        assertFalse(SupplementalMaterialPlan.hasInputs(available,
                List.of(new IngredientSpec(wool, 2))));
        assertTrue(SupplementalMaterialPlan.hasInputs(available,
                List.of(new IngredientSpec(wool, 1), new IngredientSpec(wool, 1))));
    }
}
