package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LargeVirtualStackContractTest extends BootstrapTest {

    @Test
    void virtualStacksCanRetainLargeIntegerCounts() {
        ItemStack stack = new ItemStack(Items.COBBLESTONE)
                .copyWithCount(43_000_000);

        assertEquals(43_000_000, stack.getCount());
    }
}
