package com.huanghuang.rsintegration.mods.immortalersdelight;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnchantalCoolerInventoryPolicyTest extends BootstrapTest {

    @Test
    void acceptsOnlyACompletelyIdleProcessingLane() {
        List<ItemStack> emptyInputs = List.of(ItemStack.EMPTY, ItemStack.EMPTY,
                ItemStack.EMPTY, ItemStack.EMPTY);
        assertTrue(EnchantalCoolerInventoryPolicy.isIdle(emptyInputs, ItemStack.EMPTY, 0));
        assertFalse(EnchantalCoolerInventoryPolicy.isIdle(List.of(
                new ItemStack(Items.APPLE), ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY),
                ItemStack.EMPTY, 0));
        assertFalse(EnchantalCoolerInventoryPolicy.isIdle(emptyInputs,
                new ItemStack(Items.BOWL), 0));
        assertFalse(EnchantalCoolerInventoryPolicy.isIdle(emptyInputs, ItemStack.EMPTY, 1));
    }

    @Test
    void cleanupNeverCrossesThePreExistingFuelBaseline() {
        ItemStack baseline = new ItemStack(Items.LAPIS_LAZULI, 20);
        ItemStack supplied = new ItemStack(Items.LAPIS_LAZULI);

        assertEquals(10, EnchantalCoolerInventoryPolicy.removableAddedCount(
                baseline, supplied, 44, new ItemStack(Items.LAPIS_LAZULI, 30)));
        assertEquals(0, EnchantalCoolerInventoryPolicy.removableAddedCount(
                baseline, supplied, 44, new ItemStack(Items.LAPIS_LAZULI, 20)));
        assertEquals(0, EnchantalCoolerInventoryPolicy.removableAddedCount(
                baseline, supplied, 44, new ItemStack(Items.COAL, 64)));
    }

    @Test
    void emptyBaselineAllowsOnlyThisOperationsRecordedInsertion() {
        ItemStack supplied = new ItemStack(Items.BOWL);
        assertEquals(1, EnchantalCoolerInventoryPolicy.removableAddedCount(
                ItemStack.EMPTY, supplied, 1, new ItemStack(Items.BOWL, 8)));
        assertEquals(0, EnchantalCoolerInventoryPolicy.removableAddedCount(
                ItemStack.EMPTY, supplied, 1, ItemStack.EMPTY));
    }
}
