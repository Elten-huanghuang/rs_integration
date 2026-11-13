package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.crafting.progress.CraftProgressSnapshot;
import com.huanghuang.rsintegration.crafting.progress.CraftProgressSnapshot.NodeProgress;
import com.huanghuang.rsintegration.crafting.progress.CraftProgressSnapshot.NodeState;
import com.huanghuang.rsintegration.crafting.progress.CraftProgressSnapshot.Reason;
import com.huanghuang.rsintegration.crafting.progress.CraftProgressSnapshot.WaitingInput;
import com.huanghuang.rsintegration.crafting.progress.MachineCycleProgress;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class MonitorProgressCodecTest extends BootstrapTest {
    @Test void fullAndDeltaPreserveSourceIdentityLongQuantitiesNbtAndPartialCycleCoverage() {
        var copper = new ItemStack(Items.COPPER_INGOT);
        copper.getOrCreateTag().putString("variant", "exact");
        var node = new NodeProgress(10, NodeState.BLOCKED, "test:consumer", "test", new ItemStack(Items.DIAMOND),
                2, 10, 1, "overworld@1,2,3", Reason.NONE, "", false,
                List.of(new WaitingInput(copper, 9_000_000_000L, 7), new WaitingInput(copper, 2, -1)),
                true, new MachineCycleProgress(60, 100, 1, 2));
        var snapshot = new CraftProgressSnapshot(UUID.randomUUID(), 2, CraftProgressSnapshot.Result.RUNNING,
                Reason.NONE, 0, 1, 1, null, List.of(node));
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            new CraftProgressPacket(snapshot).encode(buffer);
            assertEquals(node, CraftProgressPacket.decode(buffer).snapshot().nodes().get(0));
            assertFalse(buffer.isReadable());
            buffer.clear();
            new CraftProgressDeltaPacket(snapshot.craftId(), 1, snapshot, List.of(node)).encode(buffer);
            assertEquals(node, CraftProgressDeltaPacket.decode(buffer).changedNodes().get(0));
            assertFalse(buffer.isReadable());
            assertEquals(60, node.machineProgress().percent());
            assertTrue(CraftProgressPublisher.changedNodes(snapshot, snapshot).isEmpty());
        } finally { buffer.release(); }
    }

    @Test void rejectsUnboundedWaitListsAndInvalidMachineCounters() {
        var base = new NodeProgress(0, NodeState.RUNNING, "", "", ItemStack.EMPTY,
                0, 1, 1, "", Reason.NONE, "", false);
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buffer.writeVarInt(CraftProgressSnapshot.MAX_WAITING_INPUTS + 1);
            assertThrows(DecoderException.class, () -> CraftProgressPacket.readNodeMonitorData(buffer, base));
            buffer.clear();
            buffer.writeVarInt(0);
            buffer.writeBoolean(false);
            buffer.writeVarLong(101);
            buffer.writeVarLong(100);
            buffer.writeVarInt(1);
            buffer.writeVarInt(1);
            assertThrows(DecoderException.class, () -> CraftProgressPacket.readNodeMonitorData(buffer, base));
            assertFalse(MachineCycleProgress.of(10, 0).known());
            assertFalse(MachineCycleProgress.of(101, 100).known());
        } finally { buffer.release(); }
    }
}
