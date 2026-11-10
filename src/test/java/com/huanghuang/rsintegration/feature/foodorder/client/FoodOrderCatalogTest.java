package com.huanghuang.rsintegration.feature.foodorder.client;

import com.huanghuang.rsintegration.feature.foodorder.FoodOrderEntry;
import com.huanghuang.rsintegration.feature.foodorder.FoodOrderKind;
import com.huanghuang.rsintegration.feature.foodorder.FoodOrderSnapshot;
import com.huanghuang.rsintegration.feature.foodorder.network.FoodOrderListPacket;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FoodOrderCatalogTest extends BootstrapTest {
    @Test
    void sixThousandFoodsSurviveChunkCodecAndTailRemainsSearchable() {
        FoodOrderSnapshot original = catalog(6001, 1);
        List<FoodOrderListPacket> packets = FoodOrderListPacket.split(original, 800);
        assertEquals(8, packets.size());
        FoodOrderListAccumulator accumulator = new FoodOrderListAccumulator();
        FoodOrderSnapshot merged = null;
        for (int index = 0; index < packets.size(); index++) {
            FoodOrderListPacket packet = roundTrip(packets.get(index));
            assertEquals(index * 800, packet.offset());
            assertEquals(6001, packet.totalEntries());
            assertTrue(packet.entries().size() <= 800);
            merged = accumulator.accept(packet);
            if (index < packets.size() - 1) {
                assertNull(merged);
            }
        }
        assertNotNull(merged);
        assertEquals(6001, merged.entries().size());
        assertEquals(original.sources(), merged.sources());
        assertEquals(6001, merged.totalUneaten());
        assertTrue(merged.partial());
        FoodOrderEntry tail = merged.entries().get(6000);
        FoodOrderSearchIndex search = new FoodOrderSearchIndex(
                tail.stack().getHoverName().getString(), "test:food_6000");
        assertTrue(search.matches(FoodOrderSearchIndex.queryTerms("红烧肉 6000")));
        assertTrue(search.matches(FoodOrderSearchIndex.queryTerms("hongshaorou6000")));
        assertTrue(search.matches(FoodOrderSearchIndex.queryTerms("hsr6000")));
        assertTrue(search.matches(FoodOrderSearchIndex.queryTerms("test:food_6000")));
        assertFalse(search.matches(FoodOrderSearchIndex.queryTerms("不存在的食物")));
    }

    @Test
    void chunksMayArriveOutOfOrderAndDuplicatesDoNotCompleteEarly() {
        List<FoodOrderListPacket> packets = FoodOrderListPacket.split(catalog(3, 1), 1);
        FoodOrderListAccumulator accumulator = new FoodOrderListAccumulator();
        assertNull(accumulator.accept(packets.get(2)));
        assertNull(accumulator.accept(packets.get(2)));
        assertNull(accumulator.accept(packets.get(0)));
        FoodOrderSnapshot merged = accumulator.accept(packets.get(1));
        assertNotNull(merged);
        assertEquals(3, merged.entries().size());
        for (int index = 0; index < 3; index++) {
            assertEquals("红烧肉" + index, merged.entries().get(index).stack().getHoverName().getString());
        }
        assertNull(accumulator.accept(packets.get(0)));
    }

    @Test
    void refreshDiscardsUnfinishedOlderSnapshotWithoutMixingEntries() {
        List<FoodOrderListPacket> old = FoodOrderListPacket.split(catalog(3, 1), 1);
        List<FoodOrderListPacket> current = FoodOrderListPacket.split(catalog(2, 2), 1);
        FoodOrderListAccumulator accumulator = new FoodOrderListAccumulator();
        assertNull(accumulator.accept(old.get(0)));
        assertNull(accumulator.accept(current.get(1)));
        assertNull(accumulator.accept(old.get(1)));
        FoodOrderSnapshot merged = accumulator.accept(current.get(0));
        assertNotNull(merged);
        assertEquals(2, merged.epoch());
        assertEquals(2, merged.entries().size());
        assertNull(accumulator.accept(old.get(2)));
    }

    @Test
    void emptyCatalogStillSendsOneCompleteSnapshot() {
        List<FoodOrderListPacket> packets = FoodOrderListPacket.split(catalog(0, 1), 800);
        assertEquals(1, packets.size());
        FoodOrderSnapshot snapshot = new FoodOrderListAccumulator().accept(roundTrip(packets.get(0)));
        assertNotNull(snapshot);
        assertTrue(snapshot.entries().isEmpty());
    }

    @Test
    void invalidChunkCountsAreRejectedInsteadOfMisreadingMetadata() {
        for (int count : List.of(-1, 5001)) {
            FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
            try {
                buffer.writeVarLong(1);
                buffer.writeVarInt(count);
                assertThrows(IllegalArgumentException.class, () -> FoodOrderListPacket.decode(buffer));
            } finally {
                buffer.release();
            }
        }
    }

    @Test
    void invalidChunkRangesAreRejected() {
        FoodOrderListPacket original = FoodOrderListPacket.split(catalog(1, 1), 800).get(0);
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            FoodOrderListPacket.encode(original, buffer);
            // 最后两个单字节 VarInt 分别是 offset=0、totalEntries=1。
            buffer.setByte(buffer.writerIndex() - 2, 2);
            assertThrows(IllegalArgumentException.class, () -> FoodOrderListPacket.decode(buffer));
        } finally {
            buffer.release();
        }
    }

    @Test
    void batchConfigurationCannotCreateOversizedPackets() {
        List<FoodOrderListPacket> packets = FoodOrderListPacket.split(catalog(6001, 1), Integer.MAX_VALUE);
        assertEquals(2, packets.size());
        assertEquals(5000, packets.get(0).entries().size());
        assertEquals(1001, packets.get(1).entries().size());
    }

    private static FoodOrderListPacket roundTrip(FoodOrderListPacket packet) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            FoodOrderListPacket.encode(packet, buffer);
            FoodOrderListPacket decoded = FoodOrderListPacket.decode(buffer);
            assertEquals(0, buffer.readableBytes());
            return decoded;
        } finally {
            buffer.release();
        }
    }

    private static FoodOrderSnapshot catalog(int size, long epoch) {
        List<FoodOrderEntry> entries = new ArrayList<>(size);
        for (int index = 0; index < size; index++) {
            ItemStack stack = new ItemStack(Items.APPLE);
            stack.setHoverName(Component.literal("红烧肉" + index));
            entries.add(new FoodOrderEntry(stack, FoodOrderKind.WAITING, null, Component.empty()));
        }
        return new FoodOrderSnapshot(epoch, entries, Component.literal("背包"), size, 0, false, true);
    }
}
