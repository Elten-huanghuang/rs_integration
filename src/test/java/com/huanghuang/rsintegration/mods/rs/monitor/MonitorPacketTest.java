package com.huanghuang.rsintegration.mods.rs.monitor;

import com.huanghuang.rsintegration.crafting.batch.CraftStartedPacket;
import com.huanghuang.rsintegration.crafting.progress.CraftOutputProgress;
import com.huanghuang.rsintegration.storage.StorageBackendId;
import com.huanghuang.rsintegration.storage.StorageReference;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.world.level.Level;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class MonitorPacketTest extends BootstrapTest {
    @Test void navigationPacketsPreserveExactNetworkAndOptionalReturnTarget() {
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        var network = new StorageReference(new StorageBackendId("refinedstorage"), "v1|minecraft:overworld@0,64,0");
        try {
            for (var request : List.of(new RsOpenMonitorForCraftPacket(1, network, null),
                    new RsOpenMonitorForCraftPacket(2, null, UUID.randomUUID()),
                    new RsOpenMonitorForCraftPacket(3, null, null))) {
                request.encode(buffer);
                assertEquals(request, RsOpenMonitorForCraftPacket.decode(buffer));
            }
            for (var request : List.of(new RsReturnToCraftingTerminalPacket(17, GlobalPos.of(Level.NETHER, new BlockPos(1, 64, -4))),
                    new RsReturnToCraftingTerminalPacket(18, null))) {
                request.encode(buffer);
                assertEquals(request, RsReturnToCraftingTerminalPacket.decode(buffer));
            }
            var open = new RsOpenCraftingMonitorPacket(GlobalPos.of(Level.END, new BlockPos(1, 64, -4)));
            open.encode(buffer);
            assertEquals(open, RsOpenCraftingMonitorPacket.decode(buffer));
            var selection = new RsNetworkMonitorSelectPacket(17, UUID.randomUUID());
            selection.encode(buffer);
            assertEquals(selection, RsNetworkMonitorSelectPacket.decode(buffer));
            var result = new RsMonitorNavigationResultPacket(12, RsMonitorNavigationServer.Result.DENIED);
            result.encode(buffer);
            assertEquals(result, RsMonitorNavigationResultPacket.decode(buffer));
            assertFalse(buffer.isReadable());
            assertThrows(IllegalArgumentException.class, () -> new RsOpenMonitorForCraftPacket(4, network, UUID.randomUUID()));
            assertThrows(IllegalArgumentException.class, () -> new RsOpenMonitorForCraftPacket(0, null, null));
        } finally { buffer.release(); }
    }

    @Test void nativeMenuScopeAndActionRoundTrip() {
        var network = new StorageReference(new StorageBackendId("refinedstorage"), "v1|minecraft:overworld@0,64,0");
        var scope = new MonitorScopeState.Scope(UUID.randomUUID(), 20, network, List.of(UUID.randomUUID()));
        var packet = new RsMonitorScopePacket(17, scope);
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            packet.encode(buffer);
            assertEquals(packet, RsMonitorScopePacket.decode(buffer));
            assertFalse(buffer.isReadable());
            var cancel = new RsMonitorCancelPacket(17, scope.session(), scope.crafts().get(0));
            cancel.encode(buffer);
            assertEquals(cancel, RsMonitorCancelPacket.decode(buffer));
            new RsMonitorScopePacket(17, new MonitorScopeState.Scope(scope.session(), 21, null, List.of())).encode(buffer);
            assertNull(RsMonitorScopePacket.decode(buffer).scope().network());
            assertFalse(buffer.isReadable());
        } finally { buffer.release(); }
    }

    @Test void acceptancePreservesPreviewIdentityAndQuantitiesBeyondInt() {
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        UUID craft = UUID.randomUUID();
        var output = new CraftOutputProgress(3_000_000_000L, Long.MAX_VALUE);
        try {
            new CraftStartedPacket(craft, 23, true, new ItemStack(Items.DIAMOND), output, Long.MAX_VALUE).encode(buffer);
            var decoded = CraftStartedPacket.decode(buffer);
            assertEquals(craft, decoded.craftId());
            assertEquals(Long.MAX_VALUE, decoded.previewRequestId());
            assertEquals(output, decoded.outputProgress());
            assertEquals(Items.DIAMOND, decoded.target().getItem());
            assertFalse(buffer.isReadable());
            new CraftStartedPacket(craft, 1, false).encode(buffer);
            assertEquals(0L, CraftStartedPacket.decode(buffer).previewRequestId());
        } finally { buffer.release(); }
    }

    @Test void malformedScopeCannotAllocateAnUnboundedList() {
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buffer.writeVarInt(1).writeUUID(UUID.randomUUID()).writeVarLong(1).writeBoolean(false);
            buffer.writeVarInt(Integer.MAX_VALUE);
            assertThrows(IllegalArgumentException.class, () -> RsMonitorScopePacket.decode(buffer));
        } finally { buffer.release(); }
    }
}
