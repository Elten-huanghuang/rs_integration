package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.crafting.CraftProgressSnapshot;
import com.huanghuang.rsintegration.crafting.CraftProgressSnapshot.NodeProgress;
import com.huanghuang.rsintegration.crafting.CraftProgressSnapshot.NodeState;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import io.netty.handler.codec.DecoderException;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
    void progressPacketDecodesLegacyPayloadWithoutNodes() {
        UUID craftId = UUID.randomUUID();
        FriendlyByteBuf buf = buffer();
        buf.writeUUID(craftId);
        buf.writeVarInt(3);
        buf.writeByte(CraftProgressSnapshot.STATE_EXECUTING);
        buf.writeVarInt(1);
        buf.writeVarInt(4);
        buf.writeVarInt(2);
        buf.writeBoolean(false);

        CraftProgressSnapshot decoded = CraftProgressPacket.decode(buf).snapshot();

        assertEquals(craftId, decoded.craftId());
        assertTrue(decoded.nodes().isEmpty());
        assertEquals(0, buf.readableBytes());
    }

    @Test
    void progressPacketRoundTripsNodeProgress() {
        UUID craftId = UUID.randomUUID();
        CraftProgressSnapshot snap = new CraftProgressSnapshot(craftId, 9,
                CraftProgressSnapshot.STATE_EXECUTING, 1, 3, 1, null, List.of(
                new NodeProgress(0, NodeState.SUCCEEDED, "test:first", "generic",
                        3, 3, 0, "", "", false),
                new NodeProgress(1, NodeState.RUNNING, "test:second", "malum",
                        2, 5, 2, "test:dimension@1, 2, 3", "settling", true),
                new NodeProgress(2, NodeState.BLOCKED, "test:third", "generic",
                        0, 1, 0, "", "waiting for inputs", false)));
        FriendlyByteBuf buf = buffer();
        new CraftProgressPacket(snap).encode(buf);

        CraftProgressSnapshot decoded = CraftProgressPacket.decode(buf).snapshot();

        assertEquals(snap, decoded);
        assertEquals(0, buf.readableBytes());
    }

    @Test
    void progressPacketMapsUnknownNodeStateToUnknown() {
        FriendlyByteBuf buf = nodePayloadBuffer();
        buf.writeVarInt(17);
        buf.writeVarInt(Integer.MAX_VALUE);
        writeNodeTail(buf);

        CraftProgressSnapshot decoded = CraftProgressPacket.decode(buf).snapshot();

        assertEquals(NodeState.UNKNOWN, decoded.nodes().get(0).state());
    }

    @Test
    void progressPacketRejectsOversizedNodeCount() {
        FriendlyByteBuf buf = legacyPayloadBuffer();
        buf.writeVarInt(CraftProgressPacket.MAX_NODES + 1);

        assertThrows(DecoderException.class, () -> CraftProgressPacket.decode(buf));
    }

    @Test
    void progressPacketRejectsNegativeCounts() {
        FriendlyByteBuf buf = buffer();
        buf.writeUUID(UUID.randomUUID());
        buf.writeVarInt(1);
        buf.writeByte(CraftProgressSnapshot.STATE_EXECUTING);
        buf.writeVarInt(-1);
        buf.writeVarInt(1);
        buf.writeVarInt(0);
        buf.writeBoolean(false);

        assertThrows(DecoderException.class, () -> CraftProgressPacket.decode(buf));
    }

    @Test
    void progressPacketRejectsNegativeNodeId() {
        FriendlyByteBuf buf = nodePayloadBuffer();
        buf.writeVarInt(-1);
        buf.writeVarInt(NodeState.READY.ordinal());
        writeNodeTail(buf);

        assertThrows(DecoderException.class, () -> CraftProgressPacket.decode(buf));
    }

    @Test
    void progressPacketRejectsNegativeNodeOperationCount() {
        FriendlyByteBuf buf = nodePayloadBuffer();
        buf.writeVarInt(0);
        buf.writeVarInt(NodeState.RUNNING.ordinal());
        buf.writeUtf("test:recipe");
        buf.writeUtf("generic");
        buf.writeVarInt(-1);
        buf.writeVarInt(1);
        buf.writeVarInt(0);
        buf.writeUtf("");
        buf.writeUtf("");
        buf.writeBoolean(false);

        assertThrows(DecoderException.class, () -> CraftProgressPacket.decode(buf));
    }

    @Test
    void progressPacketRejectsOperationCountsExceedingTotal() {
        FriendlyByteBuf buf = nodePayloadBuffer();
        buf.writeVarInt(0);
        buf.writeVarInt(NodeState.RUNNING.ordinal());
        buf.writeUtf("test:recipe");
        buf.writeUtf("generic");
        buf.writeVarInt(2);
        buf.writeVarInt(2);
        buf.writeVarInt(1);
        buf.writeUtf("");
        buf.writeUtf("");
        buf.writeBoolean(false);

        assertThrows(DecoderException.class, () -> CraftProgressPacket.decode(buf));
    }

    @Test
    void nodeProgressNormalizesNullsAndInvalidCounts() {
        NodeProgress progress = new NodeProgress(0, null, null, null,
                9, -1, 4, null, null, false);

        assertEquals(NodeState.UNKNOWN, progress.state());
        assertEquals("", progress.recipeId());
        assertEquals("", progress.modTypeId());
        assertEquals(0, progress.completedOperations());
        assertEquals(0, progress.totalOperations());
        assertEquals(0, progress.runningOperations());
        assertEquals("", progress.machineLabel());
        assertEquals("", progress.detail());
    }

    @Test
    void progressPacketRejectsOversizedUtf() {
        FriendlyByteBuf buf = nodePayloadBuffer();
        buf.writeVarInt(0);
        buf.writeVarInt(NodeState.READY.ordinal());
        buf.writeUtf("x".repeat(CraftProgressPacket.MAX_RECIPE_ID_LENGTH + 1));

        assertThrows(DecoderException.class, () -> CraftProgressPacket.decode(buf));
    }

    private static FriendlyByteBuf legacyPayloadBuffer() {
        FriendlyByteBuf buf = buffer();
        buf.writeUUID(UUID.randomUUID());
        buf.writeVarInt(1);
        buf.writeByte(CraftProgressSnapshot.STATE_EXECUTING);
        buf.writeVarInt(0);
        buf.writeVarInt(1);
        buf.writeVarInt(0);
        buf.writeBoolean(false);
        return buf;
    }

    private static FriendlyByteBuf nodePayloadBuffer() {
        FriendlyByteBuf buf = legacyPayloadBuffer();
        buf.writeVarInt(1);
        return buf;
    }

    private static void writeNodeTail(FriendlyByteBuf buf) {
        buf.writeUtf("test:recipe");
        buf.writeUtf("generic");
        buf.writeVarInt(0);
        buf.writeVarInt(1);
        buf.writeVarInt(0);
        buf.writeUtf("");
        buf.writeUtf("");
        buf.writeBoolean(false);
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
