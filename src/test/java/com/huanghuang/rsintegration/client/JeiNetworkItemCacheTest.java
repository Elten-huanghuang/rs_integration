package com.huanghuang.rsintegration.client;

import com.huanghuang.rsintegration.network.packet.JeiNetworkInventoryPacket;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class JeiNetworkItemCacheTest extends BootstrapTest {
    @Test
    void emptyNbtAndTaglessJeiStackShareTheDisplayCount() {
        JeiNetworkItemCache cache = JeiNetworkItemCache.INSTANCE;
        cache.clear();
        try {
            ItemStack stored = new ItemStack(Items.DIAMOND);
            stored.setTag(new CompoundTag());
            ItemStack jei = new ItemStack(Items.DIAMOND);
            cache.accept(new JeiNetworkInventoryPacket(true, 0L, 0L, 0, 1,
                    null, List.of(new JeiNetworkInventoryPacket.Entry(stored, 7L))));

            assertEquals(7L, cache.amountForDisplay(jei));
            assertEquals(JeiNetworkInventoryPacket.key(jei),
                    JeiNetworkInventoryPacket.key(stored));
        } finally {
            cache.clear();
        }
    }

    @Test
    void taggedTetraMaterialStackMapsToItsExactNetworkVariant() {
        JeiNetworkItemCache cache = JeiNetworkItemCache.INSTANCE;
        cache.clear();
        try {
            ItemStack stored = new ItemStack(Items.DIAMOND);
            CompoundTag storedTag = new CompoundTag();
            storedTag.putString("grade", "flawless");
            stored.setTag(storedTag);

            ItemStack tetraMaterial = stored.copyWithCount(1);
            ItemStack otherVariant = new ItemStack(Items.DIAMOND);
            CompoundTag otherTag = new CompoundTag();
            otherTag.putString("grade", "rough");
            otherVariant.setTag(otherTag);

            cache.accept(new JeiNetworkInventoryPacket(true, 0L, 0L, 0, 1,
                    null, List.of(new JeiNetworkInventoryPacket.Entry(stored, 37L))));

            assertEquals(37L, cache.amountForDisplay(tetraMaterial));
            assertEquals(0L, cache.amountForDisplay(otherVariant));
            assertNotEquals(JeiNetworkInventoryPacket.key(tetraMaterial),
                    JeiNetworkInventoryPacket.key(otherVariant));
        } finally {
            cache.clear();
        }
    }

    @Test
    void amountByItemTracksVariantsAndDeltaReplacement() {
        JeiNetworkItemCache cache = JeiNetworkItemCache.INSTANCE;
        cache.clear();
        try {
            ItemStack first = new ItemStack(Items.DIAMOND);
            ItemStack second = first.copy();
            CompoundTag tag = new CompoundTag();
            tag.putString("variant", "two");
            second.setTag(tag);

            cache.accept(new JeiNetworkInventoryPacket(true, 1L, 0L, 0, 1, null,
                    List.of(new JeiNetworkInventoryPacket.Entry(first, 5L),
                            new JeiNetworkInventoryPacket.Entry(second, 7L))));
            assertEquals(12L, cache.amount(Items.DIAMOND));

            cache.accept(new JeiNetworkInventoryPacket(false, 1L, 1L, 0, 1, null,
                    List.of(new JeiNetworkInventoryPacket.Entry(first, 2L))));
            assertEquals(9L, cache.amount(Items.DIAMOND));

            cache.accept(new JeiNetworkInventoryPacket(false, 1L, 2L, 0, 1, null,
                    List.of(new JeiNetworkInventoryPacket.Entry(second, 0L))));
            assertEquals(2L, cache.amount(Items.DIAMOND));
        } finally {
            cache.clear();
        }
    }

    @Test
    void amountByItemSaturatesAtLongMaximum() {
        JeiNetworkItemCache cache = JeiNetworkItemCache.INSTANCE;
        cache.clear();
        try {
            ItemStack stack = new ItemStack(Items.DIAMOND);
            ItemStack variant = stack.copy();
            CompoundTag tag = new CompoundTag();
            tag.putString("variant", "two");
            variant.setTag(tag);
            cache.accept(new JeiNetworkInventoryPacket(true, 2L, 0L, 0, 1, null,
                    List.of(new JeiNetworkInventoryPacket.Entry(stack, Long.MAX_VALUE),
                            new JeiNetworkInventoryPacket.Entry(variant, 7L))));
            assertEquals(Long.MAX_VALUE, cache.amount(Items.DIAMOND));

            cache.accept(new JeiNetworkInventoryPacket(false, 2L, 1L, 0, 1, null,
                    List.of(new JeiNetworkInventoryPacket.Entry(stack, 1L))));
            assertEquals(8L, cache.amount(Items.DIAMOND));

            cache.accept(new JeiNetworkInventoryPacket(false, 2L, 2L, 0, 1, null,
                    List.of(new JeiNetworkInventoryPacket.Entry(variant, 0L))));
            assertEquals(1L, cache.amount(Items.DIAMOND));
        } finally {
            cache.clear();
        }
    }

    @Test
    void fullReplacementAndClearDiscardPreviousTotals() {
        JeiNetworkItemCache cache = JeiNetworkItemCache.INSTANCE;
        cache.clear();
        try {
            cache.accept(new JeiNetworkInventoryPacket(true, 3L, 0L, 0, 1, null,
                    List.of(new JeiNetworkInventoryPacket.Entry(new ItemStack(Items.DIAMOND), 9L))));
            cache.accept(new JeiNetworkInventoryPacket(true, 3L, 1L, 0, 1, null,
                    List.of(new JeiNetworkInventoryPacket.Entry(new ItemStack(Items.EMERALD), 4L))));
            assertEquals(0L, cache.amount(Items.DIAMOND));
            assertEquals(4L, cache.amount(Items.EMERALD));
            cache.clear();
            assertEquals(0L, cache.amount(Items.EMERALD));
        } finally {
            cache.clear();
        }
    }

    @Test
    void totalsOnlyPublishWhenAllChunksArriveAndIgnoreStalePackets() {
        JeiNetworkItemCache cache = JeiNetworkItemCache.INSTANCE;
        cache.clear();
        try {
            ItemStack stack = new ItemStack(Items.DIAMOND);
            cache.accept(new JeiNetworkInventoryPacket(true, 4L, 0L, 0, 1, null,
                    List.of(new JeiNetworkInventoryPacket.Entry(stack, 3L))));
            JeiNetworkInventoryPacket second = new JeiNetworkInventoryPacket(
                    true, 4L, 1L, 1, 2, null,
                    List.of(new JeiNetworkInventoryPacket.Entry(new ItemStack(Items.EMERALD), 7L)));
            cache.accept(second);
            cache.accept(second);
            assertEquals(3L, cache.amount(Items.DIAMOND));
            assertEquals(0L, cache.amount(Items.EMERALD));
            cache.accept(new JeiNetworkInventoryPacket(true, 4L, 1L, 0, 2, null,
                    List.of(new JeiNetworkInventoryPacket.Entry(stack, 5L))));
            assertEquals(5L, cache.amount(Items.DIAMOND));
            assertEquals(7L, cache.amount(Items.EMERALD));
            cache.accept(new JeiNetworkInventoryPacket(true, 4L, 0L, 0, 1, null,
                    List.of(new JeiNetworkInventoryPacket.Entry(stack, 100L))));
            assertEquals(5L, cache.amount(Items.DIAMOND));
        } finally {
            cache.clear();
        }
    }

    @Test
    void taglessDisplayUsesAllTaggedVariantsButExactMatchStillWins() {
        JeiNetworkItemCache cache = JeiNetworkItemCache.INSTANCE;
        cache.clear();
        try {
            ItemStack first = new ItemStack(Items.DIAMOND);
            CompoundTag firstTag = new CompoundTag();
            firstTag.putString("grade", "one");
            first.setTag(firstTag);
            ItemStack second = new ItemStack(Items.DIAMOND);
            CompoundTag secondTag = new CompoundTag();
            secondTag.putString("grade", "two");
            second.setTag(secondTag);
            ItemStack tagless = new ItemStack(Items.DIAMOND);
            cache.accept(new JeiNetworkInventoryPacket(true, 5L, 0L, 0, 1, null,
                    List.of(new JeiNetworkInventoryPacket.Entry(first, 5L),
                            new JeiNetworkInventoryPacket.Entry(second, 7L))));
            assertEquals(12L, cache.amountForDisplay(tagless));
            assertEquals(5L, cache.amountForDisplay(first));
            cache.accept(new JeiNetworkInventoryPacket(false, 5L, 1L, 0, 1, null,
                    List.of(new JeiNetworkInventoryPacket.Entry(tagless, 2L))));
            assertEquals(2L, cache.amountForDisplay(tagless));
            assertEquals(14L, cache.amount(Items.DIAMOND));
        } finally {
            cache.clear();
        }
    }

    @Test
    void taglessExactAmountTracksUpdatesAcrossEmptyTags() {
        JeiNetworkItemCache cache = JeiNetworkItemCache.INSTANCE;
        cache.clear();
        try {
            ItemStack tagless = new ItemStack(Items.DIAMOND);
            ItemStack emptyTag = tagless.copy();
            emptyTag.setTag(new CompoundTag());
            cache.accept(new JeiNetworkInventoryPacket(true, 6L, 0L, 0, 1, null,
                    List.of(new JeiNetworkInventoryPacket.Entry(tagless, 4L))));
            assertEquals(4L, cache.amount(emptyTag));
            cache.accept(new JeiNetworkInventoryPacket(false, 6L, 1L, 0, 1, null,
                    List.of(new JeiNetworkInventoryPacket.Entry(emptyTag, 9L))));
            assertEquals(9L, cache.amount(tagless));
            cache.accept(new JeiNetworkInventoryPacket(false, 6L, 2L, 0, 1, null,
                    List.of(new JeiNetworkInventoryPacket.Entry(tagless, 0L))));
            assertEquals(0L, cache.amount(emptyTag));
        } finally {
            cache.clear();
        }
    }
}
