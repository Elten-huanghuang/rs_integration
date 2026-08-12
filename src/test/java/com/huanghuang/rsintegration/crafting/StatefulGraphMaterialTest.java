package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StatefulGraphMaterialTest extends BootstrapTest {

    @Test
    void exactPhysicalSelectionConsumesTheVariantReturnedByTheLedger() {
        ItemStack first = tagged("first");
        ItemStack selected = tagged("selected");
        List<ItemStack> pool = new ArrayList<>(List.of(first, selected));

        ItemStack broadPlan = AsyncCraftChain.findMatching(
                pool, Ingredient.of(Items.IRON_SWORD), 1, false);
        assertEquals("first", broadPlan.getTag().getString("state"));
        assertEquals(1, pool.get(0).getCount());

        assertTrue(AsyncCraftChain.takeExactMatching(pool, selected, 1));
        assertEquals(1, pool.get(0).getCount());
        assertTrue(pool.get(1).isEmpty());
    }

    @Test
    void exactPhysicalSelectionRejectsMixedNbtVariants() {
        List<ItemStack> pool = new ArrayList<>(List.of(tagged("first"), tagged("second")));
        ItemStack requested = tagged("first");
        requested.setCount(2);

        assertFalse(AsyncCraftChain.takeExactMatching(pool, requested, 2));
        assertEquals(1, pool.get(0).getCount());
        assertEquals(1, pool.get(1).getCount());
    }

    @Test
    void brokerSelectedDamagedToolIsNotReplacedByBroadIngredientSelection() {
        ItemStack full = tagged("full");
        ItemStack damaged = tagged("damaged");
        damaged.setDamageValue(1);
        List<ItemStack> checkout = new ArrayList<>(List.of(damaged, full));

        ItemStack selected = AsyncCraftChain.findMatching(
                checkout, Ingredient.of(Items.IRON_SWORD), 1, true);

        assertEquals(1, selected.getDamageValue());
        assertEquals("damaged", selected.getTag().getString("state"));
    }

    private static ItemStack tagged(String state) {
        ItemStack stack = new ItemStack(Items.IRON_SWORD);
        stack.getOrCreateTag().putString("state", state);
        return stack;
    }
}
