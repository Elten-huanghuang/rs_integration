package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.crafting.batch.IBatchDelegate;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AsyncCraftChainProductionTest extends BootstrapTest {

    @Test
    void countsOnlyMatchingRealStacks() {
        var expected = new IBatchDelegate.ExpectedProduction(new ItemStack(Items.IRON_INGOT), 3);
        int actual = AsyncCraftChain.countMatchingProduction(List.of(
                new ItemStack(Items.IRON_INGOT, 2),
                new ItemStack(Items.GOLD_INGOT, 8),
                ItemStack.EMPTY), expected);
        assertEquals(2, actual);
    }

    @Test
    void countsDynamicNbtByItemType() {
        ItemStack actualStack = new ItemStack(Items.IRON_INGOT, 2);
        CompoundTag tag = new CompoundTag();
        tag.putString("runtime", "preserved");
        actualStack.setTag(tag);

        var expected = new IBatchDelegate.ExpectedProduction(new ItemStack(Items.IRON_INGOT), 2);
        assertEquals(2, AsyncCraftChain.countMatchingProduction(List.of(actualStack), expected));
    }

    @Test
    void nullExpectationOptsOut() {
        assertEquals(0, AsyncCraftChain.countMatchingProduction(
                List.of(new ItemStack(Items.IRON_INGOT, 64)), null));
    }
}
