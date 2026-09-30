package com.huanghuang.rsintegration.mods.biomancy;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.core.NonNullList;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BiomancyBioForgeInventoryTest extends BootstrapTest {
    @Test
    void fullBackpackDoesNotBlockCraftingAndOriginalItemsAreRestored() {
        NonNullList<ItemStack> inventory = NonNullList.withSize(36, ItemStack.EMPTY);
        for (int slot = 0; slot < inventory.size(); slot++) inventory.set(slot, new ItemStack(Items.STONE, 64));
        List<ItemStack> original = List.copyOf(inventory);
        ItemStack material = new ItemStack(Items.DIAMOND, 4);

        try (BiomancyBioForgeInventory scope = BiomancyBioForgeInventory.open(inventory, List.of(material))) {
            assertNotNull(scope);
            assertEquals(Items.DIAMOND, inventory.get(0).getItem());
            assertTrue(inventory.get(1).isEmpty());
            inventory.get(0).shrink(4);
            assertTrue(scope.remainingInputs().isEmpty());
            assertEquals(4, material.getCount());
        }
        for (int slot = 0; slot < inventory.size(); slot++) assertSame(original.get(slot), inventory.get(slot));
    }

    @Test
    void excessiveMaterialsLeaveOriginalInventoryUntouched() {
        ItemStack original = new ItemStack(Items.APPLE, 3);
        NonNullList<ItemStack> inventory = NonNullList.withSize(1, original);
        assertNull(BiomancyBioForgeInventory.open(inventory, List.of(new ItemStack(Items.DIAMOND, 65))));
        assertSame(original, inventory.get(0));
        assertEquals(3, original.getCount());
    }

    @Test
    void exceptionRestoresInventoryAndPreservesUnconsumedInputs() {
        ItemStack original = new ItemStack(Items.DIAMOND, 9);
        NonNullList<ItemStack> inventory = NonNullList.withSize(2, ItemStack.EMPTY);
        inventory.set(0, original);
        assertThrows(IllegalStateException.class, () -> {
            try (BiomancyBioForgeInventory scope = BiomancyBioForgeInventory.open(
                    inventory, List.of(new ItemStack(Items.DIAMOND, 4)))) {
                assertNotNull(scope);
                inventory.get(0).shrink(1);
                assertEquals(3, scope.remainingInputs().get(0).getCount());
                throw new IllegalStateException("模拟原版菜单异常");
            }
        });
        assertSame(original, inventory.get(0));
        assertEquals(9, original.getCount());
    }

    @Test
    void stacksWithDifferentNbtRemainSeparate() {
        NonNullList<ItemStack> inventory = NonNullList.withSize(3, ItemStack.EMPTY);
        ItemStack first = new ItemStack(Items.DIAMOND, 2);
        ItemStack second = new ItemStack(Items.DIAMOND, 3);
        first.getOrCreateTag().putString("variant", "first");
        second.getOrCreateTag().putString("variant", "second");
        try (BiomancyBioForgeInventory scope = BiomancyBioForgeInventory.open(inventory, List.of(first, second))) {
            assertNotNull(scope);
            assertEquals(2, inventory.get(0).getCount());
            assertEquals("first", inventory.get(0).getTag().getString("variant"));
            assertEquals(3, inventory.get(1).getCount());
            assertEquals("second", inventory.get(1).getTag().getString("variant"));
        }
        assertTrue(inventory.stream().allMatch(ItemStack::isEmpty));
    }
}
