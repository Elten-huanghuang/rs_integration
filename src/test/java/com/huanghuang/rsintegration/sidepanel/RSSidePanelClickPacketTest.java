package com.huanghuang.rsintegration.sidepanel;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RSSidePanelClickPacketTest extends BootstrapTest {

    @Test
    void pickBlockActionRoundTripsWithoutJeiOrGridState() {
        ItemStack target = new ItemStack(Items.STONE);
        RSSidePanelClickPacket original = new RSSidePanelClickPacket(target,
                RSSidePanelClickPacket.ACTION_PICK_BLOCK, false, null, 23L);
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());

        original.encode(buffer);
        RSSidePanelClickPacket decoded = RSSidePanelClickPacket.decode(buffer);

        assertEquals(RSSidePanelClickPacket.ACTION_PICK_BLOCK, decoded.action);
        assertEquals(23L, decoded.operationId);
        assertTrue(ItemStack.isSameItemSameTags(target, decoded.targetItem));
        assertFalse(decoded.isShift);
        assertNull(decoded.panelId);
        assertEquals(0, buffer.readableBytes());
    }
}
