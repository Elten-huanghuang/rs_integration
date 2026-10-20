package com.huanghuang.rsintegration.unifiedgrid;

import com.huanghuang.rsintegration.magic.MagicResourceStack;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import com.refinedmods.refinedstorage.api.storage.tracker.StorageTrackerEntry;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.fluids.FluidStack;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class UnifiedGridCodecTest extends BootstrapTest {
    @Test void itemAndFluidTemplatesAndBothRolesRoundTripWithoutAmountInTemplate() {
        ItemStack item = new ItemStack(Items.DIAMOND);
        item.getOrCreateTag().putString("variant", "named");
        FluidStack fluid = new FluidStack(Fluids.WATER, 1);
        fluid.getOrCreateTag().putString("variant", "tagged");
        for (GridResourceKind kind : GridResourceKind.values()) {
            UUID stored = UUID.randomUUID(), craftable = UUID.randomUUID();
            Object template = kind.isItem() ? item
                    : kind.isFluid() ? fluid
                    : new MagicResourceStack(kind.magicKind(), 1);
            long amount = kind.isMagic() ? Integer.MAX_VALUE + 123L : Integer.MAX_VALUE;
            long capacity = kind.isMagic() ? Long.MAX_VALUE : 0;
            UnifiedGridEntry original = new UnifiedGridEntry(5, amount, capacity, true, stored, craftable,
                    template, new StorageTrackerEntry(123, "player"));
            UnifiedGridEntry copy = roundTrip(kind, original);
            assertEquals(amount, copy.amount());
            assertEquals(capacity, copy.capacity());
            assertEquals(stored, copy.storedId());
            assertEquals(craftable, copy.craftableId());
            assertEquals(1, kind.amount(copy.template()));
            assertEquals(GridResourceKey.of(kind, original.template()), GridResourceKey.of(kind, copy.template()));
            assertEquals(123, copy.tracker().getTime());
        }
    }

    @Test void amountDeltaCarriesNoTemplateAndDeletionKeepsSerial() {
        UnifiedGridEntry copy = roundTrip(GridResourceKind.item(), new UnifiedGridEntry(9, 12, false, null, null, null, null));
        assertFalse(copy.metadata());
        assertNull(copy.template());
        assertEquals(12, copy.amount());
        copy = roundTrip(GridResourceKind.fluid(), new UnifiedGridEntry(9, 0, true, null, null, null, null));
        assertTrue(copy.removed());
        assertEquals(9, copy.serial());
    }

    @Test void keysFreezeTagsAndIgnoreOnlyOuterAmount() {
        ItemStack item = new ItemStack(Items.DIAMOND, 64);
        item.getOrCreateTag().putInt("Count", 7);
        GridResourceKey before = GridResourceKey.of(GridResourceKind.item(), item);
        assertEquals(before, GridResourceKey.of(GridResourceKind.item(), item.copyWithCount(1)));
        item.getOrCreateTag().putInt("Count", 8);
        assertNotEquals(before, GridResourceKey.of(GridResourceKind.item(), item));
    }

    @Test void envelopeRoundTripsAndRejectsUnknownKindsAndOversizedPayload() {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            var packet = new UnifiedGridUpdatePacket(4, UUID.randomUUID(), GridResourceKind.fluid(), 2, 7,
                    false, true, true, false, new byte[0]);
            UnifiedGridUpdatePacket.encode(packet, buf);
            var copy = UnifiedGridUpdatePacket.decode(buf);
            assertEquals(packet.session(), copy.session());
            assertEquals(packet.kind(), copy.kind());
            assertEquals(packet.sequence(), copy.sequence());
            assertTrue(copy.end());
            assertThrows(IllegalArgumentException.class, () -> GridResourceKind.fromId(99)); // 99 是未知 ID
            buf.clear();
            packet = new UnifiedGridUpdatePacket(4, UUID.randomUUID(), GridResourceKind.item(), 1, 0,
                    true, true, true, false, new byte[UnifiedGridUpdatePacket.MAX_BYTES + 1]);
            UnifiedGridUpdatePacket.encode(packet, buf);
            assertThrows(RuntimeException.class, () -> UnifiedGridUpdatePacket.decode(buf));
        } finally { buf.release(); }
    }

    private UnifiedGridEntry roundTrip(GridResourceKind kind, UnifiedGridEntry entry) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            entry.write(buf, kind);
            UnifiedGridEntry copy = UnifiedGridEntry.read(buf, kind);
            assertFalse(buf.isReadable());
            return copy;
        } finally { buf.release(); }
    }
}
