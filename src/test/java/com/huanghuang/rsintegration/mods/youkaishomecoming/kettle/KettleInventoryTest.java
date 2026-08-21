package com.huanghuang.rsintegration.mods.youkaishomecoming.kettle;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KettleInventoryTest extends BootstrapTest {
    @Test
    void idleKettleReturnsEveryResidualItem() {
        SimpleContainer items = new SimpleContainer(4);
        items.setItem(0, new ItemStack(Items.SUGAR, 2));
        items.setItem(3, new ItemStack(Items.GLASS_BOTTLE));
        List<ItemStack> returned = new ArrayList<>();

        assertEquals(3, KettleBatchDelegate.evacuateIdleItems(items, 0.0F, returned::add));
        assertEquals(2, returned.size());
        assertTrue(items.isEmpty());
    }

    @Test
    void workingKettleIsNeverEvacuated() {
        SimpleContainer items = new SimpleContainer(4);
        items.setItem(0, new ItemStack(Items.SUGAR));
        List<ItemStack> returned = new ArrayList<>();

        assertEquals(-1, KettleBatchDelegate.evacuateIdleItems(items, 0.5F, returned::add));
        assertTrue(returned.isEmpty());
        assertEquals(Items.SUGAR, items.getItem(0).getItem());
    }
}
