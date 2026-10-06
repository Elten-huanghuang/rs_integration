package com.huanghuang.rsintegration.autoeat;

import com.huanghuang.rsintegration.autoeat.network.BlacklistSyncPacket;
import com.huanghuang.rsintegration.autoeat.network.UpdateAutoEatPreferencesPacket;
import com.huanghuang.rsintegration.autoeat.network.UpdateBlacklistPacket;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutoEatBlacklistPacketTest {

    private static final ResourceLocation FOOD = new ResourceLocation("minecraft", "apple");
    private static final ResourceLocation EFFECT = new ResourceLocation("minecraft", "nausea");

    @Test
    void syncPacketRoundTripsBothBlacklists() {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        ResourceLocation bread = new ResourceLocation("minecraft", "bread");
        BlacklistSyncPacket.encode(new BlacklistSyncPacket(
                Set.of(FOOD), Set.of(EFFECT), AutoEatMode.STACK, List.of(FOOD, bread)), buffer);

        BlacklistSyncPacket decoded = BlacklistSyncPacket.decode(buffer);

        assertEquals(Set.of(FOOD), decoded.blacklist);
        assertEquals(Set.of(EFFECT), decoded.effectBlacklist);
        assertEquals(AutoEatMode.STACK, decoded.mode);
        assertEquals(List.of(FOOD, bread), decoded.selectedItems);
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
    void snapshotUpdateRoundTripsAsFullBlacklistReplacement() {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        UpdateBlacklistPacket.encode(new UpdateBlacklistPacket(Set.of(FOOD), Set.of(EFFECT)), buffer);

        UpdateBlacklistPacket decoded = UpdateBlacklistPacket.decode(buffer);

        assertTrue(decoded.snapshot);
        assertEquals(Set.of(FOOD), decoded.added);
        assertEquals(Set.of(), decoded.removed);
        assertEquals(Set.of(EFFECT), decoded.addedEffects);
        assertEquals(Set.of(), decoded.removedEffects);
    }

    @Test
    void preferenceUpdateRoundTripsModeAndSelection() {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        UpdateAutoEatPreferencesPacket.encode(
                new UpdateAutoEatPreferencesPacket(AutoEatMode.STACK, List.of(FOOD)), buffer);

        UpdateAutoEatPreferencesPacket decoded = UpdateAutoEatPreferencesPacket.decode(buffer);

        assertEquals(AutoEatMode.STACK, decoded.mode());
        assertEquals(List.of(FOOD), decoded.selectedItems());
    }

    @Test
    void rejectsOversizedPreferenceSelection() {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        buffer.writeEnum(AutoEatMode.STACK);
        buffer.writeVarInt(AutoEatPreferences.MAX_SELECTED_ITEMS + 1);

        assertThrows(DecoderException.class,
                () -> UpdateAutoEatPreferencesPacket.decode(buffer));
    }

    @Test
    void acceptsThousandsOfBlacklistedFoodsWithinWireLimit() {
        Set<ResourceLocation> added = new HashSet<>();
        for (int i = 0; i < 3000; i++) {
            added.add(new ResourceLocation("test", "food_" + i));
        }

        Set<ResourceLocation> merged = AutoEatBlacklistPolicy.merge(Set.of(), added, Set.of());

        assertEquals(3000, merged.size());
    }

    @Test
    void rejectsOversizedBlacklistAtomically() {
        Set<ResourceLocation> current = Set.of(FOOD);
        Set<ResourceLocation> added = new HashSet<>();
        for (int i = 0; i < AutoEatBlacklistPolicy.MAX_SIZE; i++) {
            added.add(new ResourceLocation("test", "food_" + i));
        }

        assertEquals(null, AutoEatBlacklistPolicy.merge(current, added, Set.of()));
        assertEquals(Set.of(FOOD), current);
    }
}
