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
}
