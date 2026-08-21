package com.huanghuang.rsintegration.autoeat;

import com.huanghuang.rsintegration.autoeat.network.BlacklistSyncPacket;
import com.huanghuang.rsintegration.autoeat.network.UpdateAutoEatPreferencesPacket;
import com.huanghuang.rsintegration.autoeat.network.UpdateBlacklistPacket;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AutoEatBlacklistPacketTest {

    private static final ResourceLocation FOOD = new ResourceLocation("minecraft", "apple");
    private static final ResourceLocation EFFECT = new ResourceLocation("minecraft", "nausea");

    @Test
    void syncPacketRoundTripsBothBlacklists() {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        BlacklistSyncPacket.encode(new BlacklistSyncPacket(
                Set.of(FOOD), Set.of(EFFECT), AutoEatMode.STACK, FOOD), buffer);

        BlacklistSyncPacket decoded = BlacklistSyncPacket.decode(buffer);

        assertEquals(Set.of(FOOD), decoded.blacklist);
        assertEquals(Set.of(EFFECT), decoded.effectBlacklist);
        assertEquals(AutoEatMode.STACK, decoded.mode);
        assertEquals(FOOD, decoded.selectedItem);
    }

    @Test
    void updatePacketRoundTripsBothDeltas() {
        ResourceLocation removedFood = new ResourceLocation("minecraft", "bread");
        ResourceLocation removedEffect = new ResourceLocation("minecraft", "poison");
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        UpdateBlacklistPacket.encode(new UpdateBlacklistPacket(
                Set.of(FOOD), Set.of(removedFood), Set.of(EFFECT), Set.of(removedEffect)), buffer);

        UpdateBlacklistPacket decoded = UpdateBlacklistPacket.decode(buffer);

        assertEquals(Set.of(FOOD), decoded.added);
        assertEquals(Set.of(removedFood), decoded.removed);
        assertEquals(Set.of(EFFECT), decoded.addedEffects);
        assertEquals(Set.of(removedEffect), decoded.removedEffects);
    }

    @Test
    void preferenceUpdateRoundTripsModeAndSelection() {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        UpdateAutoEatPreferencesPacket.encode(
                new UpdateAutoEatPreferencesPacket(AutoEatMode.STACK, FOOD), buffer);

        UpdateAutoEatPreferencesPacket decoded = UpdateAutoEatPreferencesPacket.decode(buffer);

        assertEquals(AutoEatMode.STACK, decoded.mode());
        assertEquals(FOOD, decoded.selectedItem());
    }
}
