package com.huanghuang.rsintegration.network.packet;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import io.netty.buffer.Unpooled;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JeiNetworkInventoryPacketTest extends BootstrapTest {
    @Test
    void roundTripKeepsVariantAndAmountWithoutChangingSource() {
        ItemStack source = new ItemStack(Items.DIAMOND, 7);
        CompoundTag tag = new CompoundTag();
        tag.putString("variant", "special");
        source.setTag(tag);
        JeiNetworkInventoryPacket packet = new JeiNetworkInventoryPacket(true, 2L, 3L,
                0, 1, null, List.of(new JeiNetworkInventoryPacket.Entry(source, 19L)));
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());

        packet.encode(buffer);
        JeiNetworkInventoryPacket decoded = JeiNetworkInventoryPacket.decode(buffer);

        assertEquals(7, source.getCount());
        assertEquals(1, packet.entries().get(0).stack().getCount());
        assertEquals(1, decoded.entries().get(0).stack().getCount());
        assertEquals(19L, decoded.entries().get(0).amount());
        assertEquals(JeiNetworkInventoryPacket.key(source),
                JeiNetworkInventoryPacket.key(decoded.entries().get(0).stack()));
        assertTrue(decoded.full());
        assertEquals(0, buffer.readableBytes());
    }
}
