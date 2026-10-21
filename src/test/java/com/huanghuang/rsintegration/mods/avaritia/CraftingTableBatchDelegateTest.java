package com.huanghuang.rsintegration.mods.avaritia;

import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.batch.MaterialPlan;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CraftingTableBatchDelegateTest extends BootstrapTest {

    @Test
    void recognizesAvaritiaCraftingTableTiersByRegistryId() {
        assertEquals(1, CraftingTableBatchDelegate.machineTier(
                new ResourceLocation("avaritia", "sculk_crafting_table")));
        assertEquals(2, CraftingTableBatchDelegate.machineTier(
                new ResourceLocation("avaritia", "nether_crafting_table")));
        assertEquals(3, CraftingTableBatchDelegate.machineTier(
                new ResourceLocation("avaritia", "end_crafting_table")));
        assertEquals(4, CraftingTableBatchDelegate.machineTier(
                new ResourceLocation("avaritia", "extreme_crafting_table")));
    }

    @Test
    void ignoresNonAvaritiaAndUnknownBlocks() {
        assertEquals(0, CraftingTableBatchDelegate.machineTier(
                new ResourceLocation("minecraft", "crafting_table")));
        assertEquals(0, CraftingTableBatchDelegate.machineTier(
                new ResourceLocation("avaritia", "compressed_crafting_table")));
        assertEquals(0, CraftingTableBatchDelegate.machineTier(null));
    }

    @Test
    void recognizesTiersInsidePersistedBindingKeys() {
        assertEquals(1, CraftingTableBatchDelegate.machineTierFromBindingKey(
                "avaritia_crafting||block.avaritia.sculk_crafting_table"));
        assertEquals(2, CraftingTableBatchDelegate.machineTierFromBindingKey(
                "avaritia_crafting||block.avaritia.nether_crafting_table"));
        assertEquals(3, CraftingTableBatchDelegate.machineTierFromBindingKey(
                "avaritia_crafting||block.avaritia.end_crafting_table"));
        assertEquals(4, CraftingTableBatchDelegate.machineTierFromBindingKey(
                "avaritia_crafting||block.avaritia.extreme_crafting_table"));
        assertEquals(0, CraftingTableBatchDelegate.machineTierFromBindingKey(
                "avaritia_crafting||block.avaritia.compressed_crafting_table"));
    }

    @Test
    void recognizesReAvaritiaExtremeRecipesAndBlockEntity() {
        assertEquals(true, CraftingTableBatchDelegate.isReAvaritiaExtremeRecipeClass(
                "committee.nova.mods.avaritia.common.crafting.recipe.ShapedExtremeCraftingRecipe"));
        assertEquals(true, CraftingTableBatchDelegate.isReAvaritiaExtremeRecipeClass(
                "committee.nova.mods.avaritia.common.crafting.recipe.ShapelessExtremeCraftingRecipe"));
        assertEquals(true, CraftingTableBatchDelegate.isCraftingTableBlockEntity(
                "committee.nova.mods.avaritia.common.tile.ExtremeCraftingTile"));
    }

    @Test
    void onlyDeterministicConsumableTableRecipesOptIntoBatchExecution() {
        assertEquals(true, CraftingTableBatchDelegate.supportsStableBatchClass(
                "committee.nova.mods.avaritia.common.crafting.recipe.ShapedTableCraftingRecipe"));
        assertEquals(true, CraftingTableBatchDelegate.supportsStableBatchClass(
                "committee.nova.mods.avaritia.common.crafting.recipe.ShapelessTableCraftingRecipe"));
        assertEquals(true, CraftingTableBatchDelegate.supportsStableBatchClass(
                "committee.nova.mods.avaritia.common.crafting.recipe.ShapedExtremeCraftingRecipe"));
        assertEquals(false, CraftingTableBatchDelegate.supportsStableBatchClass(
                "committee.nova.mods.avaritia.common.crafting.recipe.NoConsumeCatalystShapedRecipe"));
        assertEquals(false, CraftingTableBatchDelegate.supportsStableBatchClass(
                "example.CustomTableRecipe"));
    }

    @Test
    void preservesSparseNineByNineLayoutForNeutronCollector() {
        List<Ingredient> ingredients = new ArrayList<>(
                Collections.nCopies(9 * 9, Ingredient.EMPTY));
        ingredients.set(0, Ingredient.of(Items.IRON_BLOCK));
        ingredients.set(2, Ingredient.of(Items.QUARTZ_BLOCK));
        ingredients.set(80, Ingredient.of(Items.REDSTONE_BLOCK));

        List<IngredientSpec> layout = CraftingTableBatchDelegate.fixedSlotLayout(ingredients);
        List<Integer> expectedSlots = new ArrayList<>();
        for (int slot = 0; slot < layout.size(); slot++) {
            IngredientSpec spec = layout.get(slot);
            if (!spec.isEmpty()) {
                expectedSlots.add(slot);
            }
        }
        MaterialPlan plan = MaterialPlan.fromLegacy(layout, List.of());

        assertEquals(81, layout.size());
        assertTrue(layout.get(1).isEmpty());
        assertTrue(layout.get(79).isEmpty());
        assertEquals(List.of(0, 2, 80), expectedSlots);
        assertEquals(expectedSlots, plan.entries().stream()
                .map(MaterialPlan.Entry::inputSlot)
                .toList());
    }

    @Test
    void compactInputListCannotEraseSparsePlacementIndices() {
        List<Ingredient> ingredients = new ArrayList<>(
                Collections.nCopies(9 * 9, Ingredient.EMPTY));
        ingredients.set(0, Ingredient.of(Items.IRON_BLOCK));
        ingredients.set(80, Ingredient.of(Items.REDSTONE_BLOCK));
        MaterialPlan plan = MaterialPlan.fromLegacy(
                CraftingTableBatchDelegate.fixedSlotLayout(ingredients), List.of());

        assertEquals(List.of(0, 80), plan.entries().stream()
                .map(MaterialPlan.Entry::inputSlot)
                .toList());
    }
}
