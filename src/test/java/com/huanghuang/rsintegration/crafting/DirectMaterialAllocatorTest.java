package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.crafting.CraftingResolver.StackKey;
import com.huanghuang.rsintegration.crafting.consumption.InputConsumption;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraftforge.common.crafting.CompoundIngredient;
import net.minecraftforge.common.crafting.StrictNBTIngredient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DirectMaterialAllocatorTest extends BootstrapTest {
    @Test
    void assignsBroadIngredientWithoutStealingExactIngredientStock() {
        Ingredient broad = Ingredient.of(Items.IRON_INGOT, Items.GOLD_INGOT);
        Ingredient exactIron = Ingredient.of(Items.IRON_INGOT);

        DirectMaterialAllocator.Result result = DirectMaterialAllocator.allocate(
                List.of(new IngredientSpec(broad, 1), new IngredientSpec(exactIron, 1)),
                Map.of(new StackKey(Items.IRON_INGOT, null), 1,
                        new StackKey(Items.GOLD_INGOT, null), 1));

        assertTrue(result.feasible());
        assertEquals(2, result.allocations().stream()
                .mapToLong(DirectMaterialAllocator.Allocation::count).sum());
        assertTrue(result.allocations().stream().anyMatch(allocation ->
                allocation.ingredientIndex() == 1
                        && allocation.material().item() == Items.IRON_INGOT));
    }

    @Test
    void softPreferenceDoesNotStealStockFromExactDemand() {
        Ingredient broad = Ingredient.of(Items.WHITE_WOOL, Items.GREEN_WOOL);
        Ingredient exactWhite = Ingredient.of(Items.WHITE_WOOL);

        DirectMaterialAllocator.Result result = DirectMaterialAllocator.allocate(
                List.of(new IngredientSpec(broad, 1), new IngredientSpec(exactWhite, 1)),
                Map.of(new StackKey(Items.WHITE_WOOL, null), 1,
                        new StackKey(Items.GREEN_WOOL, null), 1));

        assertTrue(result.feasible());
        assertTrue(result.allocations().stream().anyMatch(allocation ->
                allocation.ingredientIndex() == 0
                        && allocation.material().item() == Items.GREEN_WOOL));
        assertTrue(result.allocations().stream().anyMatch(allocation ->
                allocation.ingredientIndex() == 1
                        && allocation.material().item() == Items.WHITE_WOOL));
    }

    @Test
    void broadDemandUsesPreferredStockWhenNoConstraintNeedsIt() {
        DirectMaterialAllocator.Result result = DirectMaterialAllocator.allocate(
                List.of(new IngredientSpec(
                        Ingredient.of(Items.GREEN_WOOL, Items.WHITE_WOOL), 1)),
                Map.of(new StackKey(Items.WHITE_WOOL, null), 1,
                        new StackKey(Items.GREEN_WOOL, null), 1));

        assertTrue(result.feasible());
        assertEquals(Items.WHITE_WOOL, result.allocations().get(0).material().item());
    }

    @Test
    void doesNotCountOneStackForTwoRecipeSlots() {
        DirectMaterialAllocator.Result result = DirectMaterialAllocator.allocate(
                List.of(new IngredientSpec(Ingredient.of(Items.DIAMOND), 1),
                        new IngredientSpec(Ingredient.of(Items.DIAMOND), 1)),
                Map.of(new StackKey(Items.DIAMOND, null), 1));

        assertFalse(result.feasible());
        assertEquals(1, result.missingCount());
    }

    @Test
    void preservesExactNbtDuringAllocation() {
        ItemStack expected = new ItemStack(Items.DIAMOND_SWORD);
        CompoundTag expectedTag = new CompoundTag();
        expectedTag.putInt("level", 1);
        expected.setTag(expectedTag);
        ItemStack wrong = expected.copy();
        wrong.getOrCreateTag().putInt("level", 2);

        DirectMaterialAllocator.Result result = DirectMaterialAllocator.allocate(
                List.of(new IngredientSpec(StrictNBTIngredient.of(expected), 1)),
                Map.of(new StackKey(Items.DIAMOND_SWORD, wrong.getTag().toString()), 1));

        assertFalse(result.feasible());
    }

    @Test
    void singleVariantMachineDemandDoesNotMixTagMembers() {
        ScaledIngredientSpec tag = new ScaledIngredientSpec(
                Ingredient.of(Items.APPLE, Items.GOLDEN_APPLE), 3,
                InputConsumption.consumed());
        Map<StackKey, Long> supplied = Map.of(
                new StackKey(Items.APPLE, null), 1L,
                new StackKey(Items.GOLDEN_APPLE, null), 2L);

        assertTrue(DirectMaterialAllocator.allocateScaled(List.of(tag), supplied).feasible());
        assertFalse(DirectMaterialAllocator.allocateScaledSingleVariant(
                List.of(tag), supplied).feasible());
    }

    @Test
    void singleVariantMachineDemandAcceptsOneCompleteVariant() {
        ScaledIngredientSpec tag = new ScaledIngredientSpec(
                Ingredient.of(Items.APPLE, Items.GOLDEN_APPLE), 3,
                InputConsumption.consumed());

        assertTrue(DirectMaterialAllocator.allocateScaledSingleVariant(
                List.of(tag), Map.of(new StackKey(Items.APPLE, null), 3L)).feasible());
    }

    @Test
    void singleItemPedestalsDoNotExhaustVariantSearchBeforeReachingStockedStone() {
        ScaledIngredientSpec stone = new ScaledIngredientSpec(
                Ingredient.of(Items.STONE, Items.DIORITE, Items.GRANITE), 1,
                InputConsumption.consumed());

        var result = DirectMaterialAllocator.allocateScaledSingleVariant(
                Collections.nCopies(12, stone), Map.of(new StackKey(Items.GRANITE, null), 12L));

        assertTrue(result.feasible());
        assertEquals(12L, result.allocations().stream()
                .mapToLong(DirectMaterialAllocator.Allocation::count).sum());
        assertTrue(result.allocations().stream().allMatch(
                allocation -> allocation.material().item() == Items.GRANITE));
    }

    @Test
    void unavailableVariantsDoNotExhaustSearchForMultiItemSlots() {
        ScaledIngredientSpec stone = new ScaledIngredientSpec(
                Ingredient.of(Items.STONE, Items.DIORITE, Items.GRANITE), 2,
                InputConsumption.consumed());

        var result = DirectMaterialAllocator.allocateScaledSingleVariant(
                Collections.nCopies(12, stone), Map.of(new StackKey(Items.GRANITE, null), 24L));

        assertTrue(result.feasible());
        assertEquals(24L, result.allocations().stream()
                .mapToLong(DirectMaterialAllocator.Allocation::count).sum());
    }

    @Test
    void singleVariantSlotsShareStockWithoutStealingExactInputs() {
        var broad = new ScaledIngredientSpec(Ingredient.of(Items.STONE, Items.GRANITE), 2,
                InputConsumption.consumed());
        var exact = new ScaledIngredientSpec(Ingredient.of(Items.STONE), 2,
                InputConsumption.consumed());

        var result = DirectMaterialAllocator.allocateScaledSingleVariant(List.of(broad, exact),
                Map.of(new StackKey(Items.STONE, null), 2L,
                        new StackKey(Items.GRANITE, null), 2L));

        assertTrue(result.feasible());
        assertTrue(result.allocations().stream().anyMatch(allocation ->
                allocation.ingredientIndex() == 0 && allocation.material().item() == Items.GRANITE));
    }

    @Test
    void alreadyFeasibleStockDoesNotEnumerateHundredsOfMultiItemSlots() {
        var stone = new ScaledIngredientSpec(Ingredient.of(Items.STONE, Items.GRANITE), 2,
                InputConsumption.consumed());

        var result = DirectMaterialAllocator.allocateScaledSingleVariant(Collections.nCopies(600, stone),
                Map.of(new StackKey(Items.STONE, null), 400L,
                        new StackKey(Items.GRANITE, null), 800L));

        assertTrue(result.feasible());
        assertEquals(600, result.allocations().size());
        assertTrue(result.allocations().stream().allMatch(allocation -> allocation.count() == 2L));
    }

    @Test
    void mixedFlowBranchesAroundSharedStockAndExactSlotConstraints() {
        Ingredient stones = Ingredient.of(Items.STONE, Items.GRANITE);
        var broadThree = new ScaledIngredientSpec(stones, 3, InputConsumption.consumed());
        var broadTwo = new ScaledIngredientSpec(stones, 2, InputConsumption.consumed());
        var exactTwo = new ScaledIngredientSpec(Ingredient.of(Items.STONE), 2,
                InputConsumption.consumed());

        var result = DirectMaterialAllocator.allocateScaledSingleVariant(
                List.of(broadThree, broadTwo, exactTwo),
                Map.of(new StackKey(Items.STONE, null), 4L,
                        new StackKey(Items.GRANITE, null), 3L));

        assertTrue(result.feasible());
        assertEquals(3, result.allocations().size());
        assertTrue(result.allocations().stream().anyMatch(allocation ->
                allocation.ingredientIndex() == 0 && allocation.material().item() == Items.GRANITE
                        && allocation.count() == 3L));
    }

    @Test
    void singleVariantSearchPreservesStrictNbtAlternatives() {
        ItemStack first = new ItemStack(Items.PAPER);
        first.getOrCreateTag().putInt("variant", 1);
        ItemStack second = new ItemStack(Items.PAPER);
        second.getOrCreateTag().putInt("variant", 2);
        Ingredient broad = CompoundIngredient.of(StrictNBTIngredient.of(first), StrictNBTIngredient.of(second));
        var broadThree = new ScaledIngredientSpec(broad, 3, InputConsumption.consumed());
        var exactTwo = new ScaledIngredientSpec(StrictNBTIngredient.of(first), 2, InputConsumption.consumed());

        var result = DirectMaterialAllocator.allocateScaledSingleVariant(List.of(broadThree, exactTwo),
                Map.of(StackKey.of(first, true), 4L, StackKey.of(second, true), 3L));

        assertTrue(result.feasible());
        assertEquals(2, result.allocations().size());
        assertTrue(result.allocations().stream().anyMatch(allocation ->
                allocation.ingredientIndex() == 0 && allocation.material().equals(StackKey.of(second, true))
                        && allocation.count() == 3L));
    }
}
