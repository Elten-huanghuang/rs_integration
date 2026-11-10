package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.crafting.progress.CraftProgressSnapshot;
import com.huanghuang.rsintegration.crafting.progress.CraftOutputProgress;
import com.huanghuang.rsintegration.crafting.progress.CraftProgressTracker;
import com.huanghuang.rsintegration.crafting.progress.CraftProgressSnapshot.NodeProgress;
import com.huanghuang.rsintegration.crafting.progress.CraftProgressSnapshot.NodeState;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CraftPacketCodecTest extends BootstrapTest {

    private static FriendlyByteBuf buffer() {
        return new FriendlyByteBuf(Unpooled.buffer());
    }

    @Test
    void progressPacketRoundTripsStructuredResultAndReason() {
        UUID craftId = UUID.randomUUID();
        CraftProgressSnapshot snapshot = new CraftProgressSnapshot(craftId, 9,
                CraftProgressSnapshot.Result.WAITING,
                CraftProgressSnapshot.Reason.MACHINE_BUSY,
                1, 3, 1, "diagnostic", List.of(
                new NodeProgress(0, NodeState.SUCCEEDED, "test:first", "generic",
                        new ItemStack(Items.IRON_INGOT), 3, 3, 0, "", CraftProgressSnapshot.Reason.NONE, "", false),
                new NodeProgress(1, NodeState.RUNNING, "test:second", "malum",
                        new ItemStack(Items.DIAMOND), 2, 5, 2, "test:dimension@1, 2, 3",
                        CraftProgressSnapshot.Reason.MACHINE_BUSY, "machine probe", true)));
        FriendlyByteBuf buf = buffer();

        new CraftProgressPacket(snapshot).encode(buf);
        CraftProgressSnapshot decoded = CraftProgressPacket.decode(buf).snapshot();

        assertEquals(snapshot.craftId(), decoded.craftId());
        assertEquals(snapshot.sequence(), decoded.sequence());
        assertEquals(snapshot.result(), decoded.result());
        assertEquals(snapshot.reason(), decoded.reason());
        assertEquals(snapshot.nodes().size(), decoded.nodes().size());
        for (int i = 0; i < snapshot.nodes().size(); i++) {
            NodeProgress expected = snapshot.nodes().get(i);
            NodeProgress actual = decoded.nodes().get(i);
            assertEquals(expected.nodeId(), actual.nodeId());
            assertEquals(expected.state(), actual.state());
            assertTrue(ItemStack.matches(expected.displayOutput(), actual.displayOutput()));
            assertEquals(expected.machineLabel(), actual.machineLabel());
        }
        assertEquals(0, buf.readableBytes());
        assertFalse(decoded.isTerminal());
    }

    @Test
    void terminalResultSurvivesRoundTrip() {
        CraftProgressSnapshot snapshot = new CraftProgressSnapshot(UUID.randomUUID(),
                CraftProgressSnapshot.TERMINAL_SEQUENCE,
                CraftProgressSnapshot.Result.CANCELLED,
                CraftProgressSnapshot.Reason.PLAYER_CANCELLED,
                2, 5, 0, "Player cancelled the craft");
        FriendlyByteBuf buf = buffer();

        new CraftProgressPacket(snapshot).encode(buf);
        CraftProgressSnapshot decoded = CraftProgressPacket.decode(buf).snapshot();

        assertEquals(CraftProgressSnapshot.Result.CANCELLED, decoded.result());
        assertEquals(CraftProgressSnapshot.Reason.PLAYER_CANCELLED, decoded.reason());
        assertTrue(decoded.isTerminal());
    }

    @Test
    void unknownEnumsUseSafeFallbacks() {
        FriendlyByteBuf buf = basePayload(Integer.MAX_VALUE, Integer.MAX_VALUE);
        buf.writeVarInt(1);
        buf.writeVarInt(0);
        buf.writeVarInt(Integer.MAX_VALUE);
        buf.writeUtf("test:recipe");
        buf.writeUtf("generic");
        buf.writeItem(ItemStack.EMPTY);
        buf.writeVarInt(0);
        buf.writeVarInt(1);
        buf.writeVarInt(0);
        buf.writeUtf("");
        buf.writeVarInt(Integer.MAX_VALUE);
        buf.writeUtf("");
        buf.writeBoolean(false);

        CraftProgressSnapshot decoded = CraftProgressPacket.decode(buf).snapshot();

        assertEquals(CraftProgressSnapshot.Result.RUNNING, decoded.result());
        assertEquals(CraftProgressSnapshot.Reason.UNKNOWN, decoded.reason());
        assertEquals(NodeState.UNKNOWN, decoded.nodes().get(0).state());
        assertEquals(CraftProgressSnapshot.Reason.UNKNOWN, decoded.nodes().get(0).reason());
    }

    @Test
    void progressPacketRejectsOversizedNodeCount() {
        FriendlyByteBuf buf = basePayload(0, 0);
        buf.writeVarInt(CraftProgressPacket.MAX_NODES + 1);
        assertThrows(DecoderException.class, () -> CraftProgressPacket.decode(buf));
    }

    @Test
    void progressDeltaPacketRoundTripsBaseSequenceAndChangedNodes() {
        UUID craftId = UUID.randomUUID();
        CraftProgressSnapshot snapshot = new CraftProgressSnapshot(craftId, 12,
                CraftProgressSnapshot.Result.RUNNING,
                CraftProgressSnapshot.Reason.MACHINE_BUSY,
                2, 5, 1, "waiting", List.of(
                new NodeProgress(3, NodeState.RUNNING, "test:recipe", "generic",
                        new ItemStack(Items.IRON_INGOT), 2, 4, 1, "overworld@1,2,3",
                        CraftProgressSnapshot.Reason.MACHINE_BUSY, "busy", false)));
        FriendlyByteBuf buf = buffer();
        new CraftProgressDeltaPacket(craftId, 11, snapshot, snapshot.nodes()).encode(buf);
        CraftProgressDeltaPacket decoded = CraftProgressDeltaPacket.decode(buf);

        assertEquals(craftId, decoded.craftId());
        assertEquals(11, decoded.baseSequence());
        assertEquals(12, decoded.sequence());
        assertEquals(CraftProgressSnapshot.Reason.MACHINE_BUSY, decoded.reason());
        assertEquals(1, decoded.changedNodes().size());
        assertEquals(3, decoded.changedNodes().get(0).nodeId());
        assertEquals(0, buf.readableBytes());
    }

    @Test
    void progressPacketRejectsInvalidOperationCounts() {
        FriendlyByteBuf buf = basePayload(0, 0);
        buf.writeVarInt(1);
        buf.writeVarInt(0);
        buf.writeVarInt(NodeState.RUNNING.ordinal());
        buf.writeUtf("test:recipe");
        buf.writeUtf("generic");
        buf.writeItem(ItemStack.EMPTY);
        buf.writeVarInt(2);
        buf.writeVarInt(2);
        buf.writeVarInt(1);
        buf.writeUtf("");
        buf.writeVarInt(CraftProgressSnapshot.Reason.NONE.ordinal());
        buf.writeUtf("");
        buf.writeBoolean(false);
        assertThrows(DecoderException.class, () -> CraftProgressPacket.decode(buf));
    }

    @Test
    void nodeProgressNormalizesNullsAndInvalidCounts() {
        NodeProgress progress = new NodeProgress(0, null, null, null, null,
                9, -1, 4, null, null, null, false);

        assertEquals(NodeState.UNKNOWN, progress.state());
        assertEquals(0, progress.completedOperations());
        assertEquals(0, progress.totalOperations());
        assertEquals(0, progress.runningOperations());
        assertEquals(CraftProgressSnapshot.Reason.NONE, progress.reason());
        assertEquals("", progress.technicalDetail());
    }

    @Test
    void startedAndCancelPacketsRoundTrip() {
        UUID craftId = UUID.randomUUID();
        FriendlyByteBuf started = buffer();
        ItemStack target = new ItemStack(Items.DIAMOND, 3);
        new CraftStartedPacket(craftId, 12, true, target).encode(started);
        CraftStartedPacket decodedStarted = CraftStartedPacket.decode(started);
        assertEquals(craftId, decodedStarted.craftId());
        assertEquals(12, decodedStarted.totalNodes());
        assertTrue(decodedStarted.graphMode());
        assertTrue(ItemStack.isSameItemSameTags(target, decodedStarted.target()));
        assertEquals(3, decodedStarted.target().getCount());

        FriendlyByteBuf cancel = buffer();
        new CraftCancelPacket(craftId).encode(cancel);
        assertEquals(craftId, CraftCancelPacket.decode(cancel).craftId());
    }

    private static FriendlyByteBuf basePayload(int result, int reason) {
        FriendlyByteBuf buf = buffer();
        buf.writeUUID(UUID.randomUUID());
        buf.writeVarInt(1);
        buf.writeVarInt(result);
        buf.writeVarInt(reason);
        buf.writeVarInt(0);
        buf.writeVarInt(1);
        buf.writeVarInt(0);
        buf.writeBoolean(false);
        return buf;
    }

    @Test
    void outputQuantitiesRoundTripThroughStartedFullAndDeltaPacketsWithoutStackCountLimits() {
        UUID id = UUID.randomUUID();
        CraftOutputProgress progress = new CraftOutputProgress(3_000_000_000L, 4_000_000_000L);
        var snapshot = new CraftProgressSnapshot(id, 2, CraftProgressSnapshot.Result.RUNNING,
                CraftProgressSnapshot.Reason.NONE, 0, 1, 1, null, List.of(), progress);
        FriendlyByteBuf started = buffer();
        new CraftStartedPacket(id, 1, true, new ItemStack(Items.DIAMOND), progress).encode(started);
        assertEquals(progress, CraftStartedPacket.decode(started).outputProgress());
        assertEquals(0, started.readableBytes());
        FriendlyByteBuf full = buffer();
        new CraftProgressPacket(snapshot).encode(full);
        assertEquals(progress, CraftProgressPacket.decode(full).snapshot().outputProgress());
        assertEquals(0, full.readableBytes());
        FriendlyByteBuf delta = buffer();
        new CraftProgressDeltaPacket(id, 1, snapshot, List.of()).encode(delta);
        assertEquals(progress, CraftProgressDeltaPacket.decode(delta).outputProgress());
        assertEquals(0, delta.readableBytes());
    }

    @Test
    void oldProgressPayloadsWithoutQuantityExtensionsStillDecode() {
        FriendlyByteBuf full = basePayload(0, 0);
        full.writeVarInt(0);
        assertFalse(CraftProgressPacket.decode(full).snapshot().outputProgress().known());
        UUID id = UUID.randomUUID();
        FriendlyByteBuf started = buffer();
        new CraftStartedPacket(id, 1, true).encode(started);
        assertFalse(CraftStartedPacket.decode(started).outputProgress().known());
        FriendlyByteBuf delta = buffer();
        new CraftProgressDeltaPacket(id, 1, new CraftProgressSnapshot(id, 2,
                CraftProgressSnapshot.Result.RUNNING, CraftProgressSnapshot.Reason.NONE,
                0, 1, 0, null), List.of()).encode(delta);
        assertFalse(CraftProgressDeltaPacket.decode(delta).outputProgress().known());
    }

    @Test
    void deltaRetainsActualOutputQuantityWhenNoNodeChanged() {
        UUID id = UUID.randomUUID();
        try {
            CraftProgressTracker.onStarted(new CraftStartedPacket(id, 1, true, new ItemStack(Items.DIAMOND),
                    new CraftOutputProgress(0L, 64L)));
            assertEquals(new CraftOutputProgress(0L, 64L), CraftProgressTracker.get(id).outputProgress());
            var snapshot = new CraftProgressSnapshot(id, 1, CraftProgressSnapshot.Result.RUNNING,
                    CraftProgressSnapshot.Reason.NONE, 0, 1, 1, null, List.of(), new CraftOutputProgress(4L, 64L));
            FriendlyByteBuf delta = buffer();
            new CraftProgressDeltaPacket(id, 0, snapshot, List.of()).encode(delta);
            CraftProgressTracker.onDelta(CraftProgressDeltaPacket.decode(delta));
            assertEquals(new CraftOutputProgress(4L, 64L), CraftProgressTracker.get(id).outputProgress());
        } finally {
            CraftProgressTracker.clear();
        }
    }

    @Test
    void malformedOutputQuantityExtensionsAreRejected() {
        for (long[] quantities : new long[][]{{-1L, 64L}, {65L, 64L}, {0L, 0L}}) {
            FriendlyByteBuf buf = basePayload(0, 0);
            buf.writeVarInt(0);
            buf.writeVarLong(quantities[0]);
            buf.writeVarLong(quantities[1]);
            assertThrows(DecoderException.class, () -> CraftProgressPacket.decode(buf));
        }
    }
}
