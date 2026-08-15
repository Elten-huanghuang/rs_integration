package com.huanghuang.rsintegration.network.packet;

import com.huanghuang.rsintegration.resonance.bridge.ClientDiskData;
import com.huanghuang.rsintegration.mods.lychee.LycheeVirtualCatalysts;
import com.huanghuang.rsintegration.resonance.disk.ResonanceDiskAbilities;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResonanceSyncPacketTest {

    @AfterEach
    void clearClientState() {
        ClientDiskData.clear();
    }

    @Test
    void roundTripsCatalystStateAndRevision() {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());

        int catalysts = LycheeVirtualCatalysts.POWDER_SNOW_BUCKET
                | LycheeVirtualCatalysts.GREEK_FIRE_BUCKET
                | LycheeVirtualCatalysts.DEEP_AETHER_POISON_BUCKET;
        ResonanceSyncPacket.encode(new ResonanceSyncPacket(
                17, catalysts, ResonanceDiskAbilities.MALUM_VOID_FAVOR, 42L), buffer);
        ResonanceSyncPacket decoded = ResonanceSyncPacket.decode(buffer);

        assertEquals(17, decoded.diskGems);
        assertEquals(catalysts, decoded.lycheeCatalystMask);
        assertEquals(ResonanceDiskAbilities.MALUM_VOID_FAVOR, decoded.abilityMask);
        assertEquals(42L, decoded.revision);
        assertEquals(0, buffer.readableBytes());
    }

    @Test
    void staleRevisionCannotRestoreRemovedCatalyst() {
        ClientDiskData.apply(3, LycheeVirtualCatalysts.DWARVEN_OIL_BUCKET,
                ResonanceDiskAbilities.MALUM_VOID_FAVOR, 8L);
        ClientDiskData.apply(9, LycheeVirtualCatalysts.POWDER_SNOW_BUCKET,
                0, 7L);

        assertEquals(3, ClientDiskData.getGemCount());
        assertFalse(ClientDiskData.hasPowderSnowBucket());
        assertTrue(ClientDiskData.hasLycheeCatalyst(LycheeVirtualCatalysts.DWARVEN_OIL_BUCKET));
        assertTrue(ClientDiskData.hasAbility(ResonanceDiskAbilities.MALUM_VOID_FAVOR));
        assertEquals(8L, ClientDiskData.revision());
    }

    @Test
    void clientReceivesHotSpringCatalystPresence() {
        ClientDiskData.apply(0, LycheeVirtualCatalysts.HOT_SPRING_BUCKET, 0, 1L);

        assertTrue(ClientDiskData.hasCatalyst(LycheeVirtualCatalysts.HOT_SPRING_BUCKET));
        assertFalse(ClientDiskData.hasCatalyst(LycheeVirtualCatalysts.POWDER_SNOW_BUCKET));
    }
}
