package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CraftStatusRequestPacketTest extends BootstrapTest {

    private static FriendlyByteBuf buffer() {
        return new FriendlyByteBuf(Unpooled.buffer());
    }

    @Test
    void allCraftsRequestRoundTrips() {
        FriendlyByteBuf buf = buffer();
        new CraftStatusRequestPacket().encode(buf);

        CraftStatusRequestPacket decoded = CraftStatusRequestPacket.decode(buf);
        assertTrue(decoded.requestsAll());
        assertNull(decoded.craftId());
        assertEquals(0, buf.readableBytes());
    }

    @Test
    void specificCraftRequestRoundTrips() {
        UUID craftId = UUID.randomUUID();
        FriendlyByteBuf buf = buffer();
        new CraftStatusRequestPacket(craftId).encode(buf);

        CraftStatusRequestPacket decoded = CraftStatusRequestPacket.decode(buf);
        assertFalse(decoded.requestsAll());
        assertEquals(craftId, decoded.craftId());
        assertEquals(0, buf.readableBytes());
    }
}
