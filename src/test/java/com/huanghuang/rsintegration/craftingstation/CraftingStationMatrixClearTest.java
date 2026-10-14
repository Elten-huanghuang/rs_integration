package com.huanghuang.rsintegration.craftingstation;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.TransientCraftingContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class CraftingStationMatrixClearTest extends BootstrapTest {
    @Test
    void clearingDisplayedMatrixRemovesItemsWithoutShrinkingGrid() {
        TransientCraftingContainer matrix = new TransientCraftingContainer(
                mock(AbstractContainerMenu.class), 3, 3);
        matrix.setItem(0, new ItemStack(Items.DIAMOND_SWORD));
        matrix.setItem(4, new ItemStack(Items.DIAMOND));

        AnvilTerminalState.clearDisplayedMatrix(matrix);

        assertEquals(9, matrix.getContainerSize());
        for (int i = 0; i < matrix.getContainerSize(); i++) {
            assertTrue(matrix.getItem(i).isEmpty(), "slot " + i);
        }
    }
}
