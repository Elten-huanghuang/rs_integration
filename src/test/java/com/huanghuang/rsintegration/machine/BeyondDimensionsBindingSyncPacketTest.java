package com.huanghuang.rsintegration.machine;

import com.huanghuang.rsintegration.sidepanel.data.BindingInfo;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BeyondDimensionsBindingSyncPacketTest {
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
}
