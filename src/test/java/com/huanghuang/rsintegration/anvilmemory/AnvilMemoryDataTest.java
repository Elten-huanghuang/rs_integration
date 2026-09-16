package com.huanghuang.rsintegration.anvilmemory;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnvilMemoryDataTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void keepsSixMostRecentUniqueMaterials() {
        List<ItemStack> memories = List.of();
        for (var item : List.of(Items.IRON_INGOT, Items.GOLD_INGOT, Items.DIAMOND,
                Items.EMERALD, Items.LAPIS_LAZULI, Items.REDSTONE, Items.QUARTZ)) {
            memories = AnvilMemoryData.update(memories, new ItemStack(item), true);
        }

        assertEquals(AnvilMemoryData.LIMIT, memories.size());
        assertEquals(Items.QUARTZ, memories.get(0).getItem());
        assertFalse(memories.stream().anyMatch(stack -> stack.is(Items.IRON_INGOT)));

        memories = AnvilMemoryData.update(memories, new ItemStack(Items.DIAMOND), true);
        assertEquals(Items.DIAMOND, memories.get(0).getItem());
        assertEquals(AnvilMemoryData.LIMIT, memories.size());
    }

    @Test
    void nbtCanDistinguishOrMergeEntries() {
        ItemStack first = taggedDiamond(1);
        ItemStack second = taggedDiamond(2);

        List<ItemStack> distinct = AnvilMemoryData.update(List.of(first), second, true);
        assertEquals(2, distinct.size());

        List<ItemStack> merged = AnvilMemoryData.update(List.of(first), second, false);
        assertEquals(1, merged.size());
        assertFalse(merged.get(0).hasTag());
    }

    @Test
    void lockedEntriesStayAtTopWhileUnlockedEntriesRotate() {
        List<AnvilMemoryData.MemoryEntry> memories = List.of(
                new AnvilMemoryData.MemoryEntry(new ItemStack(Items.IRON_INGOT), true),
                new AnvilMemoryData.MemoryEntry(new ItemStack(Items.GOLD_INGOT), false),
                new AnvilMemoryData.MemoryEntry(new ItemStack(Items.DIAMOND), false),
                new AnvilMemoryData.MemoryEntry(new ItemStack(Items.EMERALD), false),
                new AnvilMemoryData.MemoryEntry(new ItemStack(Items.LAPIS_LAZULI), false),
                new AnvilMemoryData.MemoryEntry(new ItemStack(Items.REDSTONE), false));

        List<AnvilMemoryData.MemoryEntry> updated = AnvilMemoryData.updateEntries(
                memories, new ItemStack(Items.QUARTZ), true);

        assertEquals(AnvilMemoryData.LIMIT, updated.size());
        assertEquals(Items.IRON_INGOT, updated.get(0).stack().getItem());
        assertEquals(Items.QUARTZ, updated.get(1).stack().getItem());
        assertEquals(Items.LAPIS_LAZULI, updated.get(5).stack().getItem());
        assertEquals(1, updated.stream().filter(AnvilMemoryData.MemoryEntry::locked).count());
    }

    @Test
    void rememberingLockedEntryDoesNotMoveOrDuplicateIt() {
        List<AnvilMemoryData.MemoryEntry> memories = List.of(
                new AnvilMemoryData.MemoryEntry(new ItemStack(Items.IRON_INGOT), true),
                new AnvilMemoryData.MemoryEntry(new ItemStack(Items.GOLD_INGOT), false));

        List<AnvilMemoryData.MemoryEntry> updated = AnvilMemoryData.updateEntries(
                memories, new ItemStack(Items.IRON_INGOT), true);

        assertEquals(2, updated.size());
        assertEquals(Items.IRON_INGOT, updated.get(0).stack().getItem());
        assertEquals(Items.GOLD_INGOT, updated.get(1).stack().getItem());
        assertTrue(updated.get(0).locked());
    }

    private static ItemStack taggedDiamond(int value) {
        ItemStack stack = new ItemStack(Items.DIAMOND);
        CompoundTag tag = new CompoundTag();
        tag.putInt("test", value);
        stack.setTag(tag);
        return stack;
    }
}
