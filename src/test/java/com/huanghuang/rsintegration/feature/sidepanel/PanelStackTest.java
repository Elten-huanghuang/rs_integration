package com.huanghuang.rsintegration.feature.sidepanel;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class PanelStackTest extends BootstrapTest {
    @Test
    void searchKeyIncludesStableNbtIdentity() {
        ItemStack first = taggedStack(false);
        ItemStack second = taggedStack(true);

        assertEquals(PanelStack.searchKeyOf(first),
                new PanelStack(UUID.randomUUID(), first, 0L, false).searchKey());
        assertEquals(PanelStack.searchKeyOf(first), PanelStack.searchKeyOf(second));
    }

    @Test
    void searchKeyStillSeparatesItemTypesAndValues() {
        ItemStack stone = new ItemStack(Items.STONE, 8);
        ItemStack dirt = new ItemStack(Items.DIRT, 8);

        assertNotEquals(PanelStack.searchKeyOf(stone),
                PanelStack.searchKeyOf(dirt));
        assertNotEquals(PanelStack.searchKeyOf(taggedStack(false)),
                PanelStack.searchKeyOf(new ItemStack(Items.STONE, 1)));
    }

    @Test
    void emptyStacksHaveEmptyIdentity() {
        assertEquals("", PanelStack.searchKeyOf(ItemStack.EMPTY));
    }

    private static ItemStack taggedStack(boolean reverse) {
        CompoundTag tag = new CompoundTag();
        if (reverse) {
            tag.putInt("second", 2);
            tag.putString("first", "a");
        } else {
            tag.putString("first", "a");
            tag.putInt("second", 2);
        }
        ItemStack stack = new ItemStack(Items.STONE, 3);
        stack.setTag(tag);
        return stack;
    }
}
