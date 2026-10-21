package com.huanghuang.rsintegration.network.machine;

import com.huanghuang.rsintegration.network.binding.MachineFavoriteKey;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MachineAutomationBlockBatchPacketTest {
    @Test
    void roundTripsBoxSelectionKeysAndDesiredState() {
        ResourceLocation overworld = new ResourceLocation("minecraft", "overworld");
        MachineFavoriteKey first = new MachineFavoriteKey(
                overworld, new BlockPos(1, 64, 2), "example||block.example.first");
        MachineFavoriteKey second = new MachineFavoriteKey(
                overworld, new BlockPos(3, 64, 4), "example||block.example.second");
        MachineAutomationBlockBatchPacket original =
                new MachineAutomationBlockBatchPacket(List.of(first, second), true);
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());

        MachineAutomationBlockBatchPacket.encode(original, buffer);
        MachineAutomationBlockBatchPacket decoded =
                MachineAutomationBlockBatchPacket.decode(buffer);

        assertEquals(List.of(first, second), decoded.keys());
        assertTrue(decoded.blocked());
    }
}
