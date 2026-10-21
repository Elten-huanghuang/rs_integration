package com.huanghuang.rsintegration.network.machine;

import com.huanghuang.rsintegration.network.binding.MachineFavoriteKey;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MachineFavoritesSyncPacketTest {

    @Test
    void roundTripsFavoritesAndAutomationBlocklist() {
        MachineFavoriteKey favorite = key(1);
        MachineFavoriteKey blocked = key(2);
        MachineFavoritesSyncPacket original =
                new MachineFavoritesSyncPacket(List.of(favorite), List.of(blocked));
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());

        MachineFavoritesSyncPacket.encode(original, buffer);
        MachineFavoritesSyncPacket decoded = MachineFavoritesSyncPacket.decode(buffer);

        assertEquals(List.of(favorite), decoded.favorites());
        assertEquals(List.of(blocked), decoded.blocked());
    }

    private static MachineFavoriteKey key(int index) {
        return new MachineFavoriteKey(new ResourceLocation("minecraft", "overworld"),
                new BlockPos(index, 64, -index), "block.example.machine_" + index);
    }
}
