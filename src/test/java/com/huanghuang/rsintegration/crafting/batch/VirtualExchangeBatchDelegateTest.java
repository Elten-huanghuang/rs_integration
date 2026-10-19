package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VirtualExchangeBatchDelegateTest extends BootstrapTest {

    @Test
    void zeroLimitUsesTheWholeRemainingExchangeAndPositiveLimitBoundsIt() {
        assertEquals(1000, VirtualExchangeBatchDelegate.boundedExecutions(1000, 0));
        assertEquals(64, VirtualExchangeBatchDelegate.boundedExecutions(1000, 64));
    }

    @Test
    void safeBatchKeepsItemStackCountsInsideIntegerRange() {
        assertEquals(1000, VirtualExchangeBatchDelegate.safeExecutions(
                1000, List.of(new ItemStack(Items.DIAMOND, 1)),
                List.of(new ItemStack(Items.EMERALD, 2))));
        assertEquals(Integer.MAX_VALUE / 4, VirtualExchangeBatchDelegate.safeExecutions(
                Integer.MAX_VALUE, List.of(new ItemStack(Items.DIAMOND, 4)),
                List.of(new ItemStack(Items.EMERALD, 2))));
    }

    @Test
    void scaledResultsSplitAtTheIntegerStackLimit() {
        List<ItemStack> results = VirtualExchangeBatchDelegate.scaledStacks(
                List.of(new ItemStack(Items.DIAMOND, Integer.MAX_VALUE)), 2);

        assertEquals(2, results.size());
        assertEquals(Integer.MAX_VALUE, results.get(0).getCount());
        assertEquals(Integer.MAX_VALUE, results.get(1).getCount());
    }

    @Test
    void requiredMaterialsUseLongMultiplicationAndIntSizedFragments() {
        List<IngredientSpec> materials = VirtualExchangeBatchDelegate.requiredMaterials(
                List.of(new ItemStack(Items.DIAMOND, 2)), 1000);

        assertEquals(1, materials.size());
        assertEquals(2000, materials.get(0).count());
        assertTrue(materials.get(0).ingredient().test(new ItemStack(Items.DIAMOND)));
    }

    @Test
    void requiredMaterialsBeyondIntegerMaximumBecomeSeveralFragments() {
        List<IngredientSpec> materials = VirtualExchangeBatchDelegate.requiredMaterials(
                List.of(new ItemStack(Items.DIAMOND, Integer.MAX_VALUE)), 2);

        assertEquals(2, materials.size());
        assertEquals(Integer.MAX_VALUE, materials.get(0).count());
        assertEquals(Integer.MAX_VALUE, materials.get(1).count());
    }
}
