package com.huanghuang.rsintegration.util;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerUtilsTest extends BootstrapTest {

    @Test
    void oversizedDeliveryIsSplitAtTheItemsOwnStackLimit() {
        ItemStack oversized = new ItemStack(Items.ENDER_PEARL, 64);
        List<Integer> insertedCounts = new ArrayList<>();

        ItemStack remainder = PlayerUtils.insertMaxSizedChunks(oversized, chunk -> {
            insertedCounts.add(chunk.getCount());
            chunk.setCount(0);
        });

        assertTrue(remainder.isEmpty());
        assertEquals(List.of(16, 16, 16, 16), insertedCounts);
    }

    @Test
    void rejectedChunksAreCombinedIntoOneRemainder() {
        ItemStack oversized = new ItemStack(Items.ENDER_PEARL, 20);

        ItemStack remainder = PlayerUtils.insertMaxSizedChunks(oversized, chunk -> { });

        assertEquals(20, remainder.getCount());
        assertEquals(Items.ENDER_PEARL, remainder.getItem());
    }
}
