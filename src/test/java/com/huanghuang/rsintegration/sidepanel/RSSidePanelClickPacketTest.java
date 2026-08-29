package com.huanghuang.rsintegration.sidepanel;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RSSidePanelClickPacketTest extends BootstrapTest {

    @Test
    void pickBlockActionRoundTripsWithoutJeiOrGridState() {
        ItemStack target = new ItemStack(Items.STONE);
        RSSidePanelClickPacket original = new RSSidePanelClickPacket(target,
                RSSidePanelClickPacket.ACTION_PICK_BLOCK, false, null, 23L);
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());

        original.encode(buffer);
        RSSidePanelClickPacket decoded = RSSidePanelClickPacket.decode(buffer);

        assertEquals(RSSidePanelClickPacket.ACTION_PICK_BLOCK, decoded.action);
        assertEquals(23L, decoded.operationId);
        assertTrue(ItemStack.isSameItemSameTags(target, decoded.targetItem));
        assertFalse(decoded.isShift);
        assertNull(decoded.panelId);
        assertEquals(0, buffer.readableBytes());
    }

    @Test
    void pickBlockCountIsBoundedByItemStackSizeAndLongStorageCounts() {
        assertEquals(0, RSSidePanelClickPacket.pickRequestCount(0L, 64));
        assertEquals(12, RSSidePanelClickPacket.pickRequestCount(12L, 64));
        assertEquals(64, RSSidePanelClickPacket.pickRequestCount(Long.MAX_VALUE, 64));
    }

    @Test
    void backendExtractionFragmentsAreCombinedWithoutLosingIdentity() {
        ItemStack first = new ItemStack(Items.STONE, 20);
        ItemStack second = new ItemStack(Items.STONE, 12);

        ItemStack merged = RSSidePanelClickPacket.mergeExactStacks(List.of(first, second));

        assertEquals(32, merged.getCount());
        assertTrue(ItemStack.isSameItemSameTags(first, merged));
        assertTrue(RSSidePanelClickPacket.mergeExactStacks(List.of(
                first, new ItemStack(Items.DIRT))).isEmpty());
    }

    @Test
    void matchingNativeMiddleClickIsCoalescedButUnrelatedHandItemsAreNot() {
        ItemStack target = new ItemStack(Items.STONE);
        ItemStack nativePick = new ItemStack(Items.STONE, 48);

        ItemStack coalesced = RSSidePanelClickPacket.coalescedNativePick(nativePick, target);

        assertEquals(48, coalesced.getCount());
        assertTrue(ItemStack.isSameItemSameTags(target, coalesced));
        assertTrue(RSSidePanelClickPacket.coalescedNativePick(
                new ItemStack(Items.DIRT), target).isEmpty());
        assertTrue(RSSidePanelClickPacket.coalescedNativePick(
                ItemStack.EMPTY, target).isEmpty());
    }
}
