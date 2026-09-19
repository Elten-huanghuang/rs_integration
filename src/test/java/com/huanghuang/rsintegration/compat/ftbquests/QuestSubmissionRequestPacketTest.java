package com.huanghuang.rsintegration.compat.ftbquests;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class QuestSubmissionRequestPacketTest {

    @Test
    void repeatCountSurvivesWireEncoding() {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        QuestSubmissionRequestPacket.encode(
                new QuestSubmissionRequestPacket(42L, false, 6), buffer);

        assertEquals(42L, buffer.readLong());
        assertFalse(buffer.readBoolean());
        assertEquals(6, buffer.readVarInt());
    }

    @Test
    void repeatCountIsClampedAtPacketBoundary() {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        QuestSubmissionRequestPacket.encode(
                new QuestSubmissionRequestPacket(42L, false, Integer.MAX_VALUE), buffer);

        buffer.readLong();
        buffer.readBoolean();
        assertEquals(1024, buffer.readVarInt());
    }
}
