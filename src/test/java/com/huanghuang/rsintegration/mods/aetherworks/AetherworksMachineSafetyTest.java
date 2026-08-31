package com.huanghuang.rsintegration.mods.aetherworks;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AetherworksMachineSafetyTest extends BootstrapTest {

    @Test
    void structureMembershipUsesExactBlockEntityIdentity() {
        Object machine = new Object();

        assertTrue(AetherworksMachineSafety.containsIdentity(List.of(new Object(), machine), machine));
        assertFalse(AetherworksMachineSafety.containsIdentity(List.of(new Object()), machine));
        assertFalse(AetherworksMachineSafety.containsIdentity(null, machine));
    }

    @Test
    void refundRequiresTheOriginalPhysicalStack() {
        ItemStack expected = new ItemStack(Items.IRON_INGOT);
        CompoundTag tag = new CompoundTag();
        tag.putInt("marker", 7);
        expected.setTag(tag);

        assertTrue(AetherworksMachineSafety.recoveredExpected(expected.copy(), expected));
        assertFalse(AetherworksMachineSafety.recoveredExpected(ItemStack.EMPTY, expected));
        assertFalse(AetherworksMachineSafety.recoveredExpected(new ItemStack(Items.GOLD_INGOT), expected));

        ItemStack wrongTag = new ItemStack(Items.IRON_INGOT);
        assertFalse(AetherworksMachineSafety.recoveredExpected(wrongTag, expected));
    }

    @Test
    void everyPlacedToolStationSlotMustBeRecovered() {
        List<ItemStack> expected = List.of(
                new ItemStack(Items.IRON_INGOT), new ItemStack(Items.STICK));

        assertTrue(AetherworksMachineSafety.recoveredExpectedSlots(
                List.of(expected.get(0).copy(), expected.get(1).copy()), expected));
        assertFalse(AetherworksMachineSafety.recoveredExpectedSlots(
                List.of(expected.get(0).copy(), ItemStack.EMPTY), expected));
        assertFalse(AetherworksMachineSafety.recoveredExpectedSlots(
                List.of(expected.get(0).copy()), expected));
    }
}
