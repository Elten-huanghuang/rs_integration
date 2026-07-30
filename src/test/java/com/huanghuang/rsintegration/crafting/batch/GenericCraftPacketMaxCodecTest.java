package com.huanghuang.rsintegration.crafting.batch;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import com.electronwill.nightconfig.core.CommentedConfig;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenericCraftPacketMaxCodecTest {

    @BeforeAll
    static void loadServerConfig() {
        CommentedConfig config = CommentedConfig.inMemory();
        RSIntegrationConfig.SERVER_SPEC.correct(config);
        RSIntegrationConfig.SERVER_SPEC.setConfig(config);
    }

    @Test
    void normalAndMaximumRequestsRoundTripTheirMode() {
        GenericCraftPacket normal = new GenericCraftPacket(id(), true);
        FriendlyByteBuf normalBuffer = new FriendlyByteBuf(Unpooled.buffer());
        normal.encode(normalBuffer);
        assertFalse(GenericCraftPacket.decode(normalBuffer).isMaximizeRequest());

        GenericCraftPacket maximum = GenericCraftPacket.maxPreview(
                id(), Map.of(), null, null, null, null, 42L);
        FriendlyByteBuf maximumBuffer = new FriendlyByteBuf(Unpooled.buffer());
        maximum.encode(maximumBuffer);
        assertTrue(GenericCraftPacket.decode(maximumBuffer).isMaximizeRequest());
    }

    @Test
    void executePacketCannotClaimMaximumMode() {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        new GenericCraftPacket(id()).encode(buffer);
        buffer.setBoolean(buffer.writerIndex() - 1, true);

        assertThrows(DecoderException.class, () -> GenericCraftPacket.decode(buffer));
    }

    private static ResourceLocation id() {
        return new ResourceLocation("minecraft", "stick");
    }
}
