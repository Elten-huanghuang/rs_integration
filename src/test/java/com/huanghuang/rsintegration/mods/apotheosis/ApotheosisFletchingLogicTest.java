package com.huanghuang.rsintegration.mods.apotheosis;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApotheosisFletchingLogicTest extends BootstrapTest {

    @Test
    void ingredientOrderMapsToNativeMenuSlots() {
        assertEquals(1, ApotheosisFletchingLogic.menuSlotForIngredient(0));
        assertEquals(2, ApotheosisFletchingLogic.menuSlotForIngredient(1));
        assertEquals(3, ApotheosisFletchingLogic.menuSlotForIngredient(2));
        assertThrows(IndexOutOfBoundsException.class,
                () -> ApotheosisFletchingLogic.menuSlotForIngredient(3));
    }

    @Test
    void outputComparisonPreservesCountAndNbt() {
        ItemStack expected = new ItemStack(Items.ARROW, 6);
        CompoundTag tag = new CompoundTag();
        tag.putString("rsi_test", "fletching");
        expected.setTag(tag);

        assertTrue(ApotheosisFletchingLogic.sameExactStack(expected.copy(), expected));
        assertFalse(ApotheosisFletchingLogic.sameExactStack(
                expected.copyWithCount(1), expected));

        ItemStack wrongTag = new ItemStack(Items.ARROW, 6);
        CompoundTag different = new CompoundTag();
        different.putString("rsi_test", "different");
        wrongTag.setTag(different);
        assertFalse(ApotheosisFletchingLogic.sameExactStack(wrongTag, expected));
        assertFalse(ApotheosisFletchingLogic.sameExactStack(
                new ItemStack(Items.SPECTRAL_ARROW, 6), expected));
    }
}
