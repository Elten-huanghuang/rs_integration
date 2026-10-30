package com.huanghuang.rsintegration.feature.foodorder;

import com.huanghuang.rsintegration.feature.foodorder.network.FoodOrderResultPacket;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FoodOrderModelTest extends BootstrapTest {

    @Test
    void wireIdsRoundTripAndReadyIsTheOnlyOrderableKind() {
        for (FoodOrderKind kind : FoodOrderKind.values()) {
            assertSame(kind, FoodOrderKind.fromWire(kind.wireId()));
            assertEquals(kind == FoodOrderKind.READY, kind.orderable());
        }
        assertSame(FoodOrderKind.WAITING, FoodOrderKind.fromWire(99));
    }

    @Test
    void entryCopiesStackAndRequiresRecipeForOrdering() {
        ItemStack stack = new ItemStack(Items.APPLE);
        FoodOrderEntry entry = new FoodOrderEntry(stack, FoodOrderKind.READY,
                new ResourceLocation("minecraft", "apple"), Component.literal("ok"));

        assertNotSame(stack, entry.stack());
        assertTrue(entry.orderable());

        FoodOrderEntry withoutRecipe = new FoodOrderEntry(stack, FoodOrderKind.READY,
                null, null);
        assertFalse(withoutRecipe.orderable());
        assertEquals(FoodOrderKind.WAITING,
                new FoodOrderEntry(null, null, null, null).kind());
    }

    @Test
    void snapshotDefensivelyCopiesEntries() {
        FoodOrderEntry entry = new FoodOrderEntry(new ItemStack(Items.APPLE),
                FoodOrderKind.MISSING, null, Component.empty());
        FoodOrderSnapshot snapshot = new FoodOrderSnapshot(7L, List.of(entry),
                null, -1, -2, false, true);

        assertEquals(1, snapshot.entries().size());
        assertEquals(0, snapshot.totalUneaten());
        assertEquals(0, snapshot.totalOrderable());
        assertEquals(Component.empty(), snapshot.sources());
        assertTrue(snapshot.partial());
    }

    @Test
    void resultPacketSurvivesCodecRoundTrip() {
        FoodOrderResultPacket original = FoodOrderResultPacket.partial(3, 2);
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        FoodOrderResultPacket.encode(original, buffer);
        FoodOrderResultPacket decoded = FoodOrderResultPacket.decode(buffer);

        assertEquals(original.code(), decoded.code());
        assertEquals(3, decoded.accepted());
        assertEquals(2, decoded.skipped());
        assertEquals(original.messageKey(), decoded.messageKey());
        assertEquals(original.extra(), decoded.extra());
    }

    @Test
    void queuedResultKeepsCountAndQueuedMessage() {
        FoodOrderResultPacket packet = FoodOrderResultPacket.queued(4);
        assertEquals(FoodOrderResultPacket.STARTED, packet.code());
        assertEquals(4, packet.accepted());
        assertEquals("rsi.food_order.result.queued", packet.messageKey());
    }

    @Test
    void failedResultKeepsMessageKeyForClientTranslation() {
        FoodOrderResultPacket packet = FoodOrderResultPacket.failed(
                "rsi.food_order.error.storage", "STORAGE_UNAVAILABLE");
        assertEquals("rsi.food_order.error.storage", packet.messageKey());
        assertEquals("STORAGE_UNAVAILABLE", packet.extra());
        assertEquals(0, packet.accepted());
    }
}
