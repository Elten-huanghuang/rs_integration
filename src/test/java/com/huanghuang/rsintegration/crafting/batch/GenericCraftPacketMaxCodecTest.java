package com.huanghuang.rsintegration.crafting.batch;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import com.electronwill.nightconfig.core.CommentedConfig;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.crafting.MachineSelectionMode;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
        FriendlyByteBuf normalPreview = new FriendlyByteBuf(Unpooled.buffer());
        new GenericCraftPacket(id(), true).encode(normalPreview);
        FriendlyByteBuf maximum = new FriendlyByteBuf(Unpooled.buffer());
        GenericCraftPacket.maxPreview(id(), Map.of(), null, null,
                null, null, 0L).encode(maximum);
        byte[] strictBytes = java.util.Arrays.copyOf(normalPreview.array(),
                normalPreview.writerIndex());
        byte[] maximumBytes = java.util.Arrays.copyOf(maximum.array(),
                maximum.writerIndex());
        int maximizeIndex = 0;
        while (maximizeIndex < strictBytes.length
                && maximizeIndex < maximumBytes.length
                && strictBytes[maximizeIndex] == maximumBytes[maximizeIndex]) {
            maximizeIndex++;
        }
        buffer.setBoolean(maximizeIndex, true);

        assertThrows(DecoderException.class, () -> GenericCraftPacket.decode(buffer));
    }

    @Test
    void machineSelectionModeRoundTrips() {
        GenericCraftPacket packet = new GenericCraftPacket(id())
                .withMachineSelectionMode(MachineSelectionMode.EXCLUSIVE);
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        packet.encode(buffer);

        assertEquals(MachineSelectionMode.EXCLUSIVE,
                GenericCraftPacket.decode(buffer).machineSelectionMode());
    }

    @Test
    void preparationModeIsExplicitAndRoundTrips() {
        GenericCraftPacket strict = new GenericCraftPacket(id());
        FriendlyByteBuf strictBuffer = new FriendlyByteBuf(Unpooled.buffer());
        strict.encode(strictBuffer);
        assertFalse(GenericCraftPacket.decode(strictBuffer).isPartialPreparation());

        GenericCraftPacket preparation = new GenericCraftPacket(id())
                .withPartialPreparation(true);
        FriendlyByteBuf preparationBuffer = new FriendlyByteBuf(Unpooled.buffer());
        preparation.encode(preparationBuffer);
        assertTrue(GenericCraftPacket.decode(preparationBuffer).isPartialPreparation());
    }

    @Test
    void dedicatedPreparationPacketForcesExplicitModeAfterRoundTrip() {
        PrepareIntermediateMaterialsPacket preparation =
                new PrepareIntermediateMaterialsPacket(new GenericCraftPacket(id()));
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        preparation.encode(buffer);

        PrepareIntermediateMaterialsPacket decoded =
                PrepareIntermediateMaterialsPacket.decode(buffer);
        assertFalse(decoded.request().isPartialPreparation());
    }

    @Test
    void dedicatedPreparationPacketRejectsPreviewRequests() {
        assertThrows(IllegalArgumentException.class, () ->
                new PrepareIntermediateMaterialsPacket(new GenericCraftPacket(id(), true)));
    }

    private static ResourceLocation id() {
        return new ResourceLocation("minecraft", "stick");
    }
}
