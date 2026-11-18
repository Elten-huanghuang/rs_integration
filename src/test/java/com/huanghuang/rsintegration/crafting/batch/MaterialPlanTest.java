package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.graph.DemandRole;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MaterialPlanTest extends BootstrapTest {

    @Test
    void separatesGraphAndSupplementalEntriesWithoutLosingSlots() {
        MaterialPlan plan = new MaterialPlan(List.of(
                new MaterialPlan.Entry("ore", new IngredientSpec(
                        Ingredient.of(Items.IRON_INGOT), 2, DemandRole.CONSUMED),
                        MaterialPlan.Allocation.GRAPH, 0),
                new MaterialPlan.Entry("catalyst", new IngredientSpec(
                        Ingredient.of(Items.BUCKET), 1, DemandRole.CATALYST),
                        MaterialPlan.Allocation.SUPPLEMENTAL, 5)));

        assertEquals(List.of("ore"), plan.graphEntries().stream()
                .map(MaterialPlan.Entry::id).toList());
        assertEquals(List.of("catalyst"), plan.supplementalEntries().stream()
                .map(MaterialPlan.Entry::id).toList());
        assertEquals(List.of("catalyst"), plan.reusableEntries().stream()
                .map(MaterialPlan.Entry::id).toList());
        assertEquals(List.of(2, 1), plan.entries().stream().map(MaterialPlan.Entry::spec)
                .map(IngredientSpec::count).toList());
    }

    @Test
    void graphSlotDeclarationNamesEachInput() {
        MaterialPlan plan = MaterialPlan.graphSlots(List.of(
                new IngredientSpec(Ingredient.of(Items.IRON_INGOT), 1),
                new IngredientSpec(Ingredient.of(Items.BUCKET), 1, DemandRole.CATALYST)));

        assertEquals(List.of("slot:0", "slot:1"),
                plan.entries().stream().map(MaterialPlan.Entry::id).toList());
        assertEquals(List.of(false, true), plan.entries().stream()
                .map(MaterialPlan.Entry::reusable).toList());
        assertEquals(List.of(0, 1), plan.entries().stream()
                .map(MaterialPlan.Entry::inputSlot).toList());
    }

    @Test
    void rejectsDuplicateMaterialIdentityOrSlot() {
        IngredientSpec spec = new IngredientSpec(Ingredient.of(Items.IRON_INGOT), 1);
        assertThrows(IllegalArgumentException.class, () -> new MaterialPlan(List.of(
                new MaterialPlan.Entry("same", spec, MaterialPlan.Allocation.GRAPH, 0),
                new MaterialPlan.Entry("same", spec, MaterialPlan.Allocation.GRAPH, 1))));
        assertThrows(IllegalArgumentException.class, () -> new MaterialPlan(List.of(
                new MaterialPlan.Entry("base", spec, MaterialPlan.Allocation.GRAPH, 0),
                new MaterialPlan.Entry("tip", spec, MaterialPlan.Allocation.SUPPLEMENTAL, 0))));
    }

}
