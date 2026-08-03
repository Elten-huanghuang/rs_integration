package com.huanghuang.rsintegration.resonance.passive;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

class PassiveItemLookupTest extends BootstrapTest {

    @Test
    void appendsEveryMatchingDiskVariantWithoutApplyingACountLimit() {
        ItemStack inventoryFlower = flower("inventory");
        ItemStack firstDiskFlower = flower("first");
        ItemStack secondDiskFlower = flower("second");

        List<ItemStack> combined = PassiveItemLookup.appendMatching(
                List.of(inventoryFlower),
                List.of(firstDiskFlower, new ItemStack(Items.STONE), secondDiskFlower),
                Items.POPPY);

        assertEquals(3, combined.size());
        assertEquals("inventory", combined.get(0).getTag().getString("Effect"));
        assertEquals("first", combined.get(1).getTag().getString("Effect"));
        assertEquals("second", combined.get(2).getTag().getString("Effect"));
        assertNotSame(firstDiskFlower, combined.get(1));
        assertNotSame(secondDiskFlower, combined.get(2));
    }

    private static ItemStack flower(String effect) {
        ItemStack stack = new ItemStack(Items.POPPY);
        stack.getOrCreateTag().putString("Effect", effect);
        return stack;
    }
}
