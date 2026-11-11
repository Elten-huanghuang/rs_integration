package com.huanghuang.rsintegration.network.machine;

import com.huanghuang.rsintegration.network.binding.MachineEndpointId;
import com.huanghuang.rsintegration.network.binding.BindingInfo;
import com.huanghuang.rsintegration.network.binding.MachineFavoriteKey;
import com.huanghuang.rsintegration.network.binding.MachineRenamePacket;
import com.huanghuang.rsintegration.network.machine.bd.BeyondDimensionsBindingSyncPacket;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MachinePacketCodecTest {
    @Test
    void roundTripsChunkHeaderAndStableEndpointId() {
        UUID bookId = UUID.randomUUID();
        MachineEndpointId endpointId = MachineEndpointId.create();
        BindingInfo binding = new BindingInfo(
                "example:terminal",
                new ResourceLocation("minecraft", "overworld"),
                new BlockPos(4, 65, -9),
                "block.example.machine",
                "Machine",
                "example:machine",
                null,
                bookId,
                endpointId, "北侧机器");
        RSBindingSyncPacket original =
                new RSBindingSyncPacket(List.of(binding), 42L, 1, 3);
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());

        original.encode(buffer);
        RSBindingSyncPacket decoded = RSBindingSyncPacket.decode(buffer);

        assertEquals(42L, decoded.generation());
        assertEquals(1, decoded.chunkIndex());
        assertEquals(3, decoded.totalChunks());
        assertEquals(bookId, decoded.bindings().get(0).bindingBookId());
        assertEquals(endpointId, decoded.bindings().get(0).endpointId());
        assertEquals("北侧机器", decoded.bindings().get(0).customName());
    }

    @Test
    void dimensionAndPositionRoundTrip() {
        UnbindMachinePacket packet = new UnbindMachinePacket(
                new ResourceLocation("minecraft", "the_nether"),
                new BlockPos(-12345, 87, 654321));
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());

        packet.encode(buffer);
        UnbindMachinePacket decoded = UnbindMachinePacket.decode(buffer);

        assertEquals(packet.dim(), decoded.dim());
        assertEquals(packet.pos(), decoded.pos());
    }

    @Test
    void renameRequestRoundTripsTheExactBindingAndResetName() {
        UUID bookId = UUID.randomUUID();
        MachineEndpointId endpointId = MachineEndpointId.create();
        for (String name : List.of("一楼熔炉", "")) {
            MachineRenamePacket packet = new MachineRenamePacket(bookId, endpointId, name);
            FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
            try {
                MachineRenamePacket.encode(packet, buffer);
                assertEquals(packet, MachineRenamePacket.decode(buffer));
                assertEquals(0, buffer.readableBytes());
            } finally {
                buffer.release();
            }
        }
    }

    @Test
    void roundTripsFavoritesAndAutomationBlocklist() {
        MachineFavoriteKey favorite = key(1);
        MachineFavoriteKey blocked = key(2);
        MachineFavoritesSyncPacket original =
                new MachineFavoritesSyncPacket(List.of(favorite), List.of(blocked));
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());

        MachineFavoritesSyncPacket.encode(original, buffer);
        MachineFavoritesSyncPacket decoded = MachineFavoritesSyncPacket.decode(buffer);

        assertEquals(List.of(favorite), decoded.favorites());
        assertEquals(List.of(blocked), decoded.blocked());
    }

    @Test
    void roundTripsBoxSelectionKeysAndDesiredState() {
        ResourceLocation overworld = new ResourceLocation("minecraft", "overworld");
        MachineFavoriteKey first = new MachineFavoriteKey(
                overworld, new BlockPos(1, 64, 2), "example||block.example.first");
        MachineFavoriteKey second = new MachineFavoriteKey(
                overworld, new BlockPos(3, 64, 4), "example||block.example.second");
        MachineAutomationBlockBatchPacket original =
                new MachineAutomationBlockBatchPacket(List.of(first, second), true);
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());

        MachineAutomationBlockBatchPacket.encode(original, buffer);
        MachineAutomationBlockBatchPacket decoded =
                MachineAutomationBlockBatchPacket.decode(buffer);

        assertEquals(List.of(first, second), decoded.keys());
        assertTrue(decoded.blocked());
    }

    @Test
    void roundTripsStandaloneChunkHeader() {
        BindingInfo binding = new BindingInfo(
                "beyonddimensions:net_terminal_item",
                new ResourceLocation("minecraft", "overworld"),
                new BlockPos(1, 64, 2),
                "block.example.machine",
                "Machine",
                "example:machine",
                null);
        BeyondDimensionsBindingSyncPacket original =
                new BeyondDimensionsBindingSyncPacket(List.of(binding), 9L, 0, 2);
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());

        original.encode(buffer);
        BeyondDimensionsBindingSyncPacket decoded =
                BeyondDimensionsBindingSyncPacket.decode(buffer);

        assertEquals(9L, decoded.generation());
        assertEquals(0, decoded.chunkIndex());
        assertEquals(2, decoded.totalChunks());
        assertEquals(binding.pos(), decoded.bindings().get(0).pos());
    }

    private static MachineFavoriteKey key(int index) {
        return new MachineFavoriteKey(new ResourceLocation("minecraft", "overworld"),
                new BlockPos(index, 64, -index), "block.example.machine_" + index);
    }
}
