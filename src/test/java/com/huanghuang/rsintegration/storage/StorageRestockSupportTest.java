package com.huanghuang.rsintegration.storage;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StorageRestockSupportTest extends BootstrapTest {
    @Test
    void exactFragmentsAreMergedWithoutChangingTheirIdentity() {
        ItemStack template = new ItemStack(Items.LAPIS_LAZULI);

        ItemStack merged = StorageRestockSupport.mergeExactStacks(template, List.of(
                new ItemStack(Items.LAPIS_LAZULI, 20),
                new ItemStack(Items.LAPIS_LAZULI, 12)),
                ItemStack::isSameItemSameTags);

        assertEquals(32, merged.getCount());
        assertTrue(ItemStack.isSameItemSameTags(template, merged));
    }

    @Test
    void unexpectedExtractionIdentityRejectsTheWholeResult() {
        ItemStack merged = StorageRestockSupport.mergeExactStacks(
                new ItemStack(Items.LAPIS_LAZULI), List.of(
                        new ItemStack(Items.LAPIS_LAZULI, 3),
                        new ItemStack(Items.DIAMOND, 1)),
                ItemStack::isSameItemSameTags);

        assertTrue(merged.isEmpty());
    }
}
