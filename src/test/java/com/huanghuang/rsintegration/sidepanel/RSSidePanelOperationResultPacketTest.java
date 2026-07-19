package com.huanghuang.rsintegration.sidepanel;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RSSidePanelOperationResultPacketTest extends BootstrapTest {

    @Test
    void roundTripPreservesAcknowledgement() {
        UUID stackId = UUID.randomUUID();
        RSSidePanelOperationResultPacket original = new RSSidePanelOperationResultPacket(
                17L, true, stackId, 32, RSSidePanelOperationResultPacket.ErrorCode.NONE);
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        original.encode(buf);

        RSSidePanelOperationResultPacket decoded = RSSidePanelOperationResultPacket.decode(buf);

        assertEquals(17L, decoded.operationId());
        assertTrue(decoded.success());
        assertEquals(stackId, decoded.stackId());
        assertEquals(32, decoded.actualCount());
        assertEquals(RSSidePanelOperationResultPacket.ErrorCode.NONE, decoded.errorCode());
        assertEquals(0, buf.readableBytes());
    }

    @Test
    void decodeRejectsTrailingBytes() {
        RSSidePanelOperationResultPacket original = new RSSidePanelOperationResultPacket(
                18L, false, null, 0,
                RSSidePanelOperationResultPacket.ErrorCode.NOTHING_TRANSFERRED);
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        original.encode(buf);
        buf.writeByte(1);

        assertThrows(IllegalArgumentException.class,
                () -> RSSidePanelOperationResultPacket.decode(buf));
    }

}
