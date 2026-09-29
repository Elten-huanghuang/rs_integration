package com.huanghuang.rsintegration.network.packet;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ConfigSyncPacketTest {

    @Test
    void repeatLimitRoundTripsWithServerConfig() {
        ConfigSyncPacket packet = packetWithRepeatLimit(512);
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());

        ConfigSyncPacket.encode(packet, buffer);
        ConfigSyncPacket decoded = ConfigSyncPacket.decode(buffer);

        assertEquals(512, decoded.repeatCountMax);
    }

    @Test
    void repeatLimitIsClampedAtProtocolBoundary() {
        FriendlyByteBuf lowBuffer = new FriendlyByteBuf(Unpooled.buffer());
        ConfigSyncPacket.encode(packetWithRepeatLimit(0), lowBuffer);
        assertEquals(1, ConfigSyncPacket.decode(lowBuffer).repeatCountMax);

        FriendlyByteBuf highBuffer = new FriendlyByteBuf(Unpooled.buffer());
        ConfigSyncPacket.encode(packetWithRepeatLimit(4096), highBuffer);
        assertEquals(1024, ConfigSyncPacket.decode(highBuffer).repeatCountMax);
    }

    private static ConfigSyncPacket packetWithRepeatLimit(int repeatLimit) {
        return new ConfigSyncPacket(true, false, 5, true, true, true, true, true, true, true,
                true, true, false, true, true, 8, repeatLimit);
    }
}
