package com.huanghuang.rsintegration.network.machine;

import com.huanghuang.rsintegration.network.binding.MachineEndpointId;
import com.huanghuang.rsintegration.network.binding.BindingInfo;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RSBindingSyncPacketTest {
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
                endpointId);
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
    }
}
