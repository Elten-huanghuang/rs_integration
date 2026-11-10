package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.graph.DemandRole;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class BulkOperationMaterialsTest extends BootstrapTest {
    @Test
    void fiftyMillionOperationsKeepTemplatesAndLongSourceQuantities() {
        int operations = 50_000_000;
        var specs = List.of(new IngredientSpec(Ingredient.of(Items.IRON_INGOT), 64),
                IngredientSpec.EMPTY, new IngredientSpec(Ingredient.of(Items.GOLD_INGOT), 2));
        ItemStack tagged = new ItemStack(Items.GOLD_INGOT, operations * 2);
        tagged.getOrCreateTag().putString("origin", "producer");
        var initial = List.of(new ItemStack(Items.IRON_INGOT, Integer.MAX_VALUE),
                new ItemStack(Items.IRON_INGOT, (int) (64L * operations - Integer.MAX_VALUE)));
        var plan = BulkOperationMaterials.tryCreate(specs, operations, initial, List.of(tagged));
        assertNotNull(plan);
        assertEquals(2, plan.initialMaterials(0, operations).size());
        assertEquals(64L * operations, plan.initialMaterials(0, operations).stream().mapToLong(ItemStack::getCount).sum());
        assertEquals(Integer.MAX_VALUE / 64, plan.maxBatchSize());
        var batch = plan.batchMaterials(operations - 3, 3);
        assertEquals(192, batch.get(0).getCount());
        assertTrue(batch.get(1).isEmpty());
        assertEquals(6, batch.get(2).getCount());
        assertEquals("producer", batch.get(2).getTag().getString("origin"));
        batch.get(2).getTag().putString("origin", "mutated");
        assertEquals("producer", plan.batchMaterials(0, 1).get(2).getTag().getString("origin"));
        assertEquals(Integer.MAX_VALUE, initial.get(0).getCount());
        assertEquals(operations * 2, tagged.getCount());
        assertTrue(plan.initialRemainder().isEmpty());
        assertTrue(plan.producerRemainder().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> plan.batchMaterials(0, operations));
    }

    @Test
    void repeatedSlotsShareOnePoolWithoutDoubleCounting() {
        var need = new IngredientSpec(Ingredient.of(Items.IRON_INGOT), 2);
        var stock = List.of(new ItemStack(Items.IRON_INGOT, 11));
        var plan = BulkOperationMaterials.tryCreate(List.of(need, need), 2, stock, List.of());
        assertNotNull(plan);
        assertEquals(3, plan.initialRemainder().get(0).getCount());
        assertEquals(8, plan.initialMaterials(0, 2).stream().mapToInt(ItemStack::getCount).sum());
        assertNull(BulkOperationMaterials.tryCreate(List.of(need, need), 3, stock, List.of()));
        assertEquals(11, stock.get(0).getCount());
    }

    @Test
    void mixedNbtMixedSourcesAndReusableInputsKeepOriginalAllocationPath() {
        var spec = new IngredientSpec(Ingredient.of(Items.IRON_INGOT), 1);
        ItemStack tagged = new ItemStack(Items.IRON_INGOT, 4);
        tagged.getOrCreateTag().putString("variant", "other");
        var stock = List.of(new ItemStack(Items.IRON_INGOT, 4), tagged);
        assertNull(BulkOperationMaterials.tryCreate(List.of(spec), 8, stock, List.of()));
        assertNull(BulkOperationMaterials.tryCreate(List.of(spec), 8,
                List.of(new ItemStack(Items.IRON_INGOT, 4)), List.of(new ItemStack(Items.IRON_INGOT, 4))));
        assertNull(BulkOperationMaterials.tryCreate(List.of(new IngredientSpec(spec.ingredient(), 1, DemandRole.CATALYST)),
                4, stock, List.of()));
    }
}
