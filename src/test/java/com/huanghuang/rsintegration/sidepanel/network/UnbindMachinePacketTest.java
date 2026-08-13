package com.huanghuang.rsintegration.sidepanel.network;

import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UnbindMachinePacketTest {

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
}
