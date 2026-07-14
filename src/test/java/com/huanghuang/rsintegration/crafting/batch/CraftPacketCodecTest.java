package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.crafting.CraftProgressSnapshot;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Round-trip codec tests for the stage-4 craft progress/cancel packets.
 * Verifies encode → decode preserves every field, including the terminal
 * sequence sentinel and the optional failed-step string.
 */
class CraftPacketCodecTest extends BootstrapTest {

    private static FriendlyByteBuf buffer() {
        return new FriendlyByteBuf(Unpooled.buffer());
    }

    @Test
    void progressPacketRoundTripsWithoutFailedStep() {
        UUID craftId = UUID.randomUUID();
        CraftProgressSnapshot snap = new CraftProgressSnapshot(craftId, 7,
                CraftProgressSnapshot.STATE_EXECUTING, 3, 10, 2, null);
        FriendlyByteBuf buf = buffer();
        new CraftProgressPacket(snap).encode(buf);

        CraftProgressSnapshot decoded = CraftProgressPacket.decode(buf).snapshot();
        assertEquals(snap, decoded);
        assertNull(decoded.failedStep());
        assertEquals(0, buf.readableBytes(), "decode must consume the whole buffer");
    }

    @Test
    void progressPacketRoundTripsWithFailedStep() {
        UUID craftId = UUID.randomUUID();
        CraftProgressSnapshot snap = new CraftProgressSnapshot(craftId, 42,
                CraftProgressSnapshot.STATE_STOPPING, 5, 8, 1, "minecraft:diamond_block");
        FriendlyByteBuf buf = buffer();
        new CraftProgressPacket(snap).encode(buf);

        CraftProgressSnapshot decoded = CraftProgressPacket.decode(buf).snapshot();
        assertEquals(snap, decoded);
        assertEquals("minecraft:diamond_block", decoded.failedStep());
        assertTrue(decoded.isTerminal(), "STOPPING state is terminal");
    }

    @Test
    void progressPacketPreservesTerminalSequenceSentinel() {
        UUID craftId = UUID.randomUUID();
        CraftProgressSnapshot snap = new CraftProgressSnapshot(craftId,
                CraftProgressSnapshot.TERMINAL_SEQUENCE,
                CraftProgressSnapshot.STATE_EXECUTING, 10, 10, 0, null);
        FriendlyByteBuf buf = buffer();
        new CraftProgressPacket(snap).encode(buf);

        CraftProgressSnapshot decoded = CraftProgressPacket.decode(buf).snapshot();
        assertEquals(CraftProgressSnapshot.TERMINAL_SEQUENCE, decoded.sequence());
        assertTrue(decoded.isTerminal(), "terminal sequence sentinel survives round-trip");
    }

    @Test
    void startedPacketRoundTrips() {
        UUID craftId = UUID.randomUUID();
        FriendlyByteBuf buf = buffer();
        new CraftStartedPacket(craftId, 12, true).encode(buf);

        CraftStartedPacket decoded = CraftStartedPacket.decode(buf);
        assertEquals(craftId, decoded.craftId());
        assertEquals(12, decoded.totalNodes());
        assertTrue(decoded.graphMode());
        assertEquals(0, buf.readableBytes());
    }

    @Test
    void startedPacketRoundTripsNonGraphMode() {
        UUID craftId = UUID.randomUUID();
        FriendlyByteBuf buf = buffer();
        new CraftStartedPacket(craftId, 0, false).encode(buf);

        CraftStartedPacket decoded = CraftStartedPacket.decode(buf);
        assertEquals(craftId, decoded.craftId());
        assertEquals(0, decoded.totalNodes());
        assertFalse(decoded.graphMode());
    }

    @Test
    void cancelPacketRoundTrips() {
        UUID craftId = UUID.randomUUID();
        FriendlyByteBuf buf = buffer();
        new CraftCancelPacket(craftId).encode(buf);

        CraftCancelPacket decoded = CraftCancelPacket.decode(buf);
        assertEquals(craftId, decoded.craftId());
        assertEquals(0, buf.readableBytes());
    }
}
