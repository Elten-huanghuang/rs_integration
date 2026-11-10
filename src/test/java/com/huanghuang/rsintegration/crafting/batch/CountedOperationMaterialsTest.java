package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.batch.CountedOperationMaterials.Origin;
import com.huanghuang.rsintegration.crafting.batch.CountedOperationMaterials.Pool;
import com.huanghuang.rsintegration.crafting.consumption.InputConsumption;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class CountedOperationMaterialsTest extends BootstrapTest {
    private static final IngredientSpec INPUT = new IngredientSpec(Ingredient.of(Items.IRON_INGOT), 3);

    @Test
    void fiftyMillionMixedOperationsKeepOnlyVariantSegments() {
        int count = 50_000_000;
        ItemStack variant = new ItemStack(Items.IRON_INGOT);
        variant.getOrCreateTag().putString("variant", "b");
        Pool stock = new Pool().add(Origin.PRODUCER, new ItemStack(Items.IRON_INGOT), 30_000_001L)
                .add(Origin.INITIAL, new ItemStack(Items.IRON_INGOT), 44_999_999L)
                .add(Origin.INITIAL, variant, 75_000_000L);
        var plan = new CountedOperationMaterials.Builder(count, 2).add(INPUT, stock).build();
        assertEquals(2, plan.segmentCount());
        assertEquals(25_000_000, plan.compatibleBatchSize(0, count));
        assertEquals(3, plan.batchMaterials(1, count - 1, 1).get(0).getCount());
        assertEquals("b", plan.batchMaterials(1, count - 1, 1).get(0).getTag().getString("variant"));
        assertEquals(30_000_001L, quantity(plan.materials(Origin.PRODUCER, 0, count)));
        assertEquals(119_999_999L, quantity(plan.materials(Origin.INITIAL, 0, count)));
        assertTrue(stock.remaining(Origin.INITIAL).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> plan.batchMaterials(0, 24_999_999, 2));
    }

    @Test
    void sourceBoundaryInsideAnOperationPreservesExactRefundCounts() {
        var plan = new CountedOperationMaterials.Builder(3, 1).add(INPUT,
                new Pool().add(Origin.PRODUCER, new ItemStack(Items.IRON_INGOT), 4)
                        .add(Origin.INITIAL, new ItemStack(Items.IRON_INGOT), 5)).build();
        assertEquals(1, quantity(plan.materials(Origin.PRODUCER, 1, 1)));
        assertEquals(2, quantity(plan.materials(Origin.INITIAL, 1, 1)));
        assertEquals(3, quantity(plan.materials(Origin.INITIAL, 2, 1)));
        assertEquals(9, plan.batchMaterials(0, 0, 3).get(0).getCount());
    }

    @Test
    void reusableMaterialsFollowWorkerIdentityAndDoNotEnterConsumedRefunds() {
        IngredientSpec catalyst = new IngredientSpec(Ingredient.of(Items.DIAMOND), 1, InputConsumption.catalyst());
        ItemStack second = new ItemStack(Items.DIAMOND);
        second.getOrCreateTag().putString("owner", "second");
        var plan = new CountedOperationMaterials.Builder(1_000_000, 2)
                .addGraphReusable(catalyst, new Pool().add(Origin.PRODUCER, List.of(new ItemStack(Items.DIAMOND))),
                        new Pool().add(Origin.INITIAL, List.of(second)))
                .add(INPUT, new Pool().add(Origin.INITIAL, new ItemStack(Items.IRON_INGOT), 3_000_000))
                .build();
        assertEquals("second", plan.batchMaterials(1, 0, 8).get(0).getTag().getString("owner"));
        assertNull(plan.batchMaterials(0, 31, 8).get(0).getTag());
        assertEquals(24, quantity(plan.materials(Origin.INITIAL, 0, 8)));
        assertEquals(1, quantity(plan.reusableMaterials(Origin.PRODUCER, 0)));
        assertEquals(1, quantity(plan.reusableMaterials(Origin.INITIAL, 1)));
    }

    @Test
    void toolQuantityUsesRemainingDurabilityAndWorkerShares() {
        ItemStack knife = new ItemStack(Items.IRON_SWORD);
        knife.setDamageValue(knife.getMaxDamage() - 10);
        IngredientSpec tool = new IngredientSpec(Ingredient.of(Items.IRON_SWORD), 1, InputConsumption.durability(1));
        var plan = new CountedOperationMaterials.Builder(50, 2).add(tool,
                new Pool().add(Origin.INITIAL, knife, 6)).build();
        assertEquals(3, plan.batchMaterials(0, 0, 8).get(0).getCount());
        assertEquals(3, plan.batchMaterials(1, 8, 8).get(0).getCount());
        assertTrue(plan.matches(List.of(tool)));
        assertTrue(plan.materials(Origin.INITIAL, 0, 50).isEmpty());
        assertEquals(6, quantity(plan.reusableMaterials(Origin.INITIAL, 0))
                + quantity(plan.reusableMaterials(Origin.INITIAL, 1)));
    }

    @Test
    void graphToolsAreSharedBetweenWorkersBeforeBuyingAdditionalTools() {
        ItemStack knife = new ItemStack(Items.IRON_SWORD);
        knife.setDamageValue(knife.getMaxDamage() - 10);
        IngredientSpec tool = new IngredientSpec(Ingredient.of(Items.IRON_SWORD), 1, InputConsumption.durability(1));
        var plan = new CountedOperationMaterials.Builder(40, 2).addGraphReusable(tool,
                new Pool().add(Origin.PRODUCER, knife, 4), new Pool()).build();
        assertEquals(2, quantity(plan.reusableMaterials(Origin.PRODUCER, 1)));
    }

    @Test
    void differentDamageToolsRetainTheirExactStockAndRefunds() {
        ItemStack damaged = new ItemStack(Items.IRON_SWORD);
        damaged.setDamageValue(damaged.getMaxDamage() - 3);
        ItemStack other = new ItemStack(Items.IRON_SWORD);
        other.setDamageValue(other.getMaxDamage() - 7);
        IngredientSpec tool = new IngredientSpec(Ingredient.of(Items.IRON_SWORD), 1, InputConsumption.durability(1));
        var plan = new CountedOperationMaterials.Builder(10, 1).add(tool,
                new Pool().add(Origin.PRODUCER, List.of(damaged)).add(Origin.INITIAL, List.of(other))).build();
        assertEquals(2, plan.mixedReusableStock(0).get(0).size());
        assertEquals(damaged.getDamageValue(), plan.reusableMaterials(Origin.PRODUCER, 0).get(0).getDamageValue());
        assertEquals(other.getDamageValue(), plan.reusableMaterials(Origin.INITIAL, 0).get(0).getDamageValue());
    }

    @Test
    void randomWindowsPartitionTheOriginalSourcesWithoutDuplication() {
        Random random = new Random(817L);
        for (int trial = 0; trial < 200; trial++) {
            int operations = 1 + random.nextInt(300);
            long producer = random.nextInt(operations * 3 + 1);
            var plan = new CountedOperationMaterials.Builder(operations, 1).add(INPUT,
                    new Pool().add(Origin.PRODUCER, new ItemStack(Items.IRON_INGOT), producer)
                            .add(Origin.INITIAL, new ItemStack(Items.IRON_INGOT), operations * 3L - producer)).build();
            long foundProducer = 0, foundInitial = 0;
            for (int first = 0; first < operations;) {
                int count = Math.min(operations - first, 1 + random.nextInt(17));
                foundProducer += quantity(plan.materials(Origin.PRODUCER, first, count));
                foundInitial += quantity(plan.materials(Origin.INITIAL, first, count));
                first += count;
            }
            assertEquals(producer, foundProducer);
            assertEquals(operations * 3L - producer, foundInitial);
        }
    }

    @Test
    void variantsCannotBeFusedToFabricateOnePhysicalInput() {
        ItemStack tagged = new ItemStack(Items.IRON_INGOT, 2);
        tagged.getOrCreateTag().putInt("variant", 2);
        Pool stock = new Pool().add(Origin.VIRTUAL, List.of(new ItemStack(Items.IRON_INGOT, 1), tagged));
        assertThrows(IllegalArgumentException.class,
                () -> new CountedOperationMaterials.Builder(1, 1).add(INPUT, stock));
    }

    @Test
    void sparseSlotsAndReturnedContainersRemainInTheirPhysicalPositions() {
        var container = new IngredientSpec(Ingredient.of(Items.WATER_BUCKET), 1, InputConsumption.containerReturned());
        var plan = new CountedOperationMaterials.Builder(8, 1)
                .add(IngredientSpec.EMPTY, new Pool())
                .add(container, new Pool().add(Origin.VIRTUAL, new ItemStack(Items.WATER_BUCKET), 8)).build();
        assertTrue(plan.batchMaterials(0, 0, 3).get(0).isEmpty());
        assertEquals(3, plan.batchMaterials(0, 0, 3).get(1).getCount());
        assertEquals(5, quantity(plan.materials(Origin.VIRTUAL, 3, 5)));
    }

    private static long quantity(List<ItemStack> stacks) { return stacks.stream().mapToLong(ItemStack::getCount).sum(); }
}
