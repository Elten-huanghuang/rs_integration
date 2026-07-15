package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.crafting.CraftProgressSnapshot;
import com.huanghuang.rsintegration.crafting.CraftProgressSnapshot.NodeProgress;
import com.huanghuang.rsintegration.crafting.CraftProgressSnapshot.NodeState;
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
                        3, 3, 0, "", CraftProgressSnapshot.Reason.NONE, "", false),
                new NodeProgress(1, NodeState.RUNNING, "test:second", "malum",
                        2, 5, 2, "test:dimension@1, 2, 3",
                        CraftProgressSnapshot.Reason.MACHINE_BUSY, "machine probe", true)));
        FriendlyByteBuf buf = buffer();

        new CraftProgressPacket(snapshot).encode(buf);
        CraftProgressSnapshot decoded = CraftProgressPacket.decode(buf).snapshot();

        assertEquals(snapshot, decoded);
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
    void progressPacketRejectsInvalidOperationCounts() {
        FriendlyByteBuf buf = basePayload(0, 0);
        buf.writeVarInt(1);
        buf.writeVarInt(0);
        buf.writeVarInt(NodeState.RUNNING.ordinal());
        buf.writeUtf("test:recipe");
        buf.writeUtf("generic");
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
        NodeProgress progress = new NodeProgress(0, null, null, null,
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
}
