package com.huanghuang.rsintegration.feature.sidepanel;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RSSidePanelPacketTest extends BootstrapTest {

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

    @Test
    void rejectsOversizedEncodedBatch() {
        var packet = new RSSidePanelDeltaPacket(Collections.nCopies(
                RSSidePanelDeltaPacket.MAX_ENTRIES + 1, null));

        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        assertThrows(IllegalArgumentException.class, () -> packet.encode(buffer));
    }

    @Test
    void rejectsOversizedDecodedBatch() {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        buffer.writeVarInt(RSSidePanelDeltaPacket.MAX_ENTRIES + 1);

        assertThrows(DecoderException.class, () -> RSSidePanelDeltaPacket.decode(buffer));
    }

    @Test
    void roundTripPreservesAcknowledgement() {
        UUID stackId = UUID.randomUUID();
        RSSidePanelOperationResultPacket original = new RSSidePanelOperationResultPacket(
                17L, true, stackId, 32, RSSidePanelOperationResultPacket.ErrorCode.NONE);
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        original.encode(buffer);

        RSSidePanelOperationResultPacket decoded = RSSidePanelOperationResultPacket.decode(buffer);

        assertEquals(17L, decoded.operationId());
        assertTrue(decoded.success());
        assertEquals(stackId, decoded.stackId());
        assertEquals(32, decoded.actualCount());
        assertEquals(RSSidePanelOperationResultPacket.ErrorCode.NONE, decoded.errorCode());
        assertEquals(0, buffer.readableBytes());
    }

    @Test
    void decodeRejectsTrailingBytes() {
        RSSidePanelOperationResultPacket original = new RSSidePanelOperationResultPacket(
                18L, false, null, 0,
                RSSidePanelOperationResultPacket.ErrorCode.NOTHING_TRANSFERRED);
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        original.encode(buffer);
        buffer.writeByte(1);

        assertThrows(IllegalArgumentException.class,
                () -> RSSidePanelOperationResultPacket.decode(buffer));
    }
}
