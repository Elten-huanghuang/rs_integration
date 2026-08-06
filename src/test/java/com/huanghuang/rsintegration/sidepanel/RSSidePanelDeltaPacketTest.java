package com.huanghuang.rsintegration.sidepanel;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertThrows;

class RSSidePanelDeltaPacketTest {
    @Test
    void rejectsOversizedEncodedBatch() {
        var packet = new RSSidePanelDeltaPacket(Collections.nCopies(
                RSSidePanelDeltaPacket.MAX_ENTRIES + 1, null));

        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        assertThrows(IllegalArgumentException.class, () -> packet.encode(buf));
    }

    @Test
    void rejectsOversizedDecodedBatch() {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        buf.writeVarInt(RSSidePanelDeltaPacket.MAX_ENTRIES + 1);

        assertThrows(DecoderException.class, () -> RSSidePanelDeltaPacket.decode(buf));
    }
}
