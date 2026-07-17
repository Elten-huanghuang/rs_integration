package com.huanghuang.rsintegration.mods.goety;

import com.huanghuang.rsintegration.crafting.batch.IBatchDelegate;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GoetyBatchDelegateTest extends BootstrapTest {

    @Test
    void matchesRuntimeNbtOutputByItemType() {
        ItemStack expected = new ItemStack(Items.IRON_LEGGINGS);
        ItemStack enchanted = expected.copy();
        CompoundTag tag = new CompoundTag();
        tag.putString("runtime_enchantment", "present");
        enchanted.setTag(tag);

        assertTrue(IBatchDelegate.matchesProducedItem(enchanted, expected));
        assertFalse(IBatchDelegate.matchesProducedItem(
                new ItemStack(Items.IRON_CHESTPLATE), expected));
    }
}
