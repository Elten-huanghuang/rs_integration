package com.huanghuang.rsintegration.crafting.planning;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PlanningProgressPacketTest {
    @Test
    void roundTripsCorrelatedProgress() {
        PlanningProgressSnapshot expected = new PlanningProgressSnapshot(
                42L, 7L, new ResourceLocation("minecraft", "crafting_table"),
                PlanningProgressSnapshot.State.RUNNING,
                PlanningProgressSnapshot.Phase.DEMAND_TREE,
                1_825L, Component.translatable("rsi.planning.phase.demand_tree"));
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());

        PlanningProgressPacket.encode(new PlanningProgressPacket(expected), buffer);
        PlanningProgressSnapshot actual = PlanningProgressPacket.decode(buffer).snapshot();

        assertEquals(expected.requestId(), actual.requestId());
        assertEquals(expected.requestGeneration(), actual.requestGeneration());
        assertEquals(expected.recipeId(), actual.recipeId());
        assertEquals(expected.state(), actual.state());
        assertEquals(expected.phase(), actual.phase());
        assertEquals(expected.elapsedMillis(), actual.elapsedMillis());
        assertEquals(expected.detail(), actual.detail());
    }

    @Test
    void rejectsZeroRequestId() {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        buffer.writeVarLong(0L);
        buffer.writeVarLong(1L);

        assertThrows(DecoderException.class, () -> PlanningProgressPacket.decode(buffer));
    }

    @Test
    void rejectsUnknownStateOrdinal() {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        buffer.writeVarLong(1L);
        buffer.writeVarLong(1L);
        buffer.writeResourceLocation(new ResourceLocation("minecraft", "stone"));
        buffer.writeVarInt(999);
        buffer.writeVarInt(0);

        assertThrows(DecoderException.class, () -> PlanningProgressPacket.decode(buffer));
    }
}
