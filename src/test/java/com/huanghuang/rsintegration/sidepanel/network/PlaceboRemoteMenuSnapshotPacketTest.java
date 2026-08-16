package com.huanghuang.rsintegration.sidepanel.network;

import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PlaceboRemoteMenuSnapshotPacketTest {
    @Test
    void blockEntitySnapshotRoundTrips() {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", "apotheosis:reforging_table");
        tag.putInt("CustomValue", 42);
        PlaceboRemoteMenuSnapshotPacket packet = new PlaceboRemoteMenuSnapshotPacket(
                new BlockPos(-17, 64, 123), 9876, tag);
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());

        PlaceboRemoteMenuSnapshotPacket.encode(packet, buffer);
        PlaceboRemoteMenuSnapshotPacket decoded = PlaceboRemoteMenuSnapshotPacket.decode(buffer);

        assertEquals(packet.pos(), decoded.pos());
        assertEquals(packet.blockStateId(), decoded.blockStateId());
        assertEquals(tag, decoded.blockEntityTag());
    }

    @Test
    void clearPacketRoundTripsWithoutNbt() {
        PlaceboRemoteMenuSnapshotPacket packet = new PlaceboRemoteMenuSnapshotPacket(
                BlockPos.ZERO, 0, null);
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());

        PlaceboRemoteMenuSnapshotPacket.encode(packet, buffer);
        PlaceboRemoteMenuSnapshotPacket decoded = PlaceboRemoteMenuSnapshotPacket.decode(buffer);

        assertNull(decoded.blockEntityTag());
    }
}
