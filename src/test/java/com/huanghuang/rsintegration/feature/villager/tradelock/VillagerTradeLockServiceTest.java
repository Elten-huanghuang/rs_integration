package com.huanghuang.rsintegration.feature.villager.tradelock;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VillagerTradeLockServiceTest extends BootstrapTest {

    @Test
    void sharedBookmarkLookupKeepsPlayersAndNbtIsolated() {
        ServerPlayer first = mock(ServerPlayer.class);
        ServerPlayer second = mock(ServerPlayer.class);
        when(first.getUUID()).thenReturn(UUID.randomUUID());
        when(second.getUUID()).thenReturn(UUID.randomUUID());
        try {
            VillagerTradeLockService.replace(first, List.of(taggedDiamond("locked", 1)));
            assertTrue(VillagerTradeLockService.isBookmarked(first, taggedDiamond("locked", 64)));
            assertFalse(VillagerTradeLockService.isBookmarked(first, taggedDiamond("other", 1)));
            assertFalse(VillagerTradeLockService.isBookmarked(second, taggedDiamond("locked", 1)));
            assertFalse(VillagerTradeLockService.isBookmarked(null, taggedDiamond("locked", 1)));
            VillagerTradeLockService.remove(first.getUUID());
            assertFalse(VillagerTradeLockService.isBookmarked(first, taggedDiamond("locked", 1)));
        } finally {
            VillagerTradeLockService.clear();
        }
    }

    @Test
    void sameItemAndNbtMatchRegardlessOfCount() {
        ItemStack bookmark = taggedDiamond("locked", 1);
        ItemStack result = taggedDiamond("locked", 64);

        var index = VillagerTradeLockService.BookmarkIndex.create(List.of(bookmark));

        assertTrue(index.contains(result));
    }

    @Test
    void differentNbtDoesNotMatch() {
        var index = VillagerTradeLockService.BookmarkIndex.create(
                List.of(taggedDiamond("locked", 1)));

        assertFalse(index.contains(taggedDiamond("other", 1)));
    }

    @Test
    void anyBookmarkedOfferBlocksTheOfferList() {
        var index = VillagerTradeLockService.BookmarkIndex.create(
                List.of(taggedDiamond("locked", 1)));
        MerchantOffers offers = new MerchantOffers();
        offers.add(offer(new ItemStack(Items.EMERALD)));
        offers.add(offer(taggedDiamond("locked", 3)));

        assertTrue(VillagerTradeLockService.containsBookmarkedResult(index, offers));
    }

    @Test
    void emptyBookmarksOrOffersDoNotBlock() {
        var emptyIndex = VillagerTradeLockService.BookmarkIndex.create(List.of());
        var populatedIndex = VillagerTradeLockService.BookmarkIndex.create(
                List.of(new ItemStack(Items.DIAMOND)));

        assertFalse(VillagerTradeLockService.containsBookmarkedResult(
                emptyIndex, new MerchantOffers()));
        assertFalse(VillagerTradeLockService.containsBookmarkedResult(
                populatedIndex, new MerchantOffers()));
    }

    private static MerchantOffer offer(ItemStack result) {
        return new MerchantOffer(new ItemStack(Items.EMERALD), result, 12, 1, 0.05F);
    }

    private static ItemStack taggedDiamond(String variant, int count) {
        ItemStack stack = new ItemStack(Items.DIAMOND, count);
        CompoundTag tag = new CompoundTag();
        tag.putString("variant", variant);
        stack.setTag(tag);
        return stack;
    }
}
