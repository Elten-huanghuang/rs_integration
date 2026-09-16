package com.huanghuang.rsintegration.client;

import com.huanghuang.rsintegration.network.packet.JeiNetworkInventoryPacket;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
