package com.huanghuang.rsintegration.crafting.plan;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Bounds checks for corrupt plan and graph collection lengths. */
class PlanResponsePacketBoundsTest extends BootstrapTest {

    private static final int OVERSIZED_COUNT = 4097;

    @Test
    void roundTripPreservesNetShortageSeparatelyFromGrossDemandAndNbt() {
        var intermediate = com.huanghuang.rsintegration.crafting.tree.IngredientKey.of(
                new ItemStack(net.minecraft.world.item.Items.IRON_INGOT));
        ItemStack strict = new ItemStack(net.minecraft.world.item.Items.POTION);
        strict.getOrCreateTag().putString("Potion", "minecraft:water");
        var raw = com.huanghuang.rsintegration.crafting.tree.IngredientKey.of(strict);
        var materials = Map.of(intermediate, new PlanResponse.Availability(64, 0, 0),
                raw, new PlanResponse.Availability(10, 2, 3));
        var plan = new PlanResponse(false, "", new ItemStack(net.minecraft.world.item.Items.DIAMOND),
                List.of(), materials, List.of(), "test:recipe");
        FriendlyByteBuf buf = buffer();
        try {
            new PlanResponsePacket(plan).encode(buf);
            PlanResponse decoded = PlanResponsePacket.decode(buf).plan();
            org.junit.jupiter.api.Assertions.assertEquals(materials, decoded.materials());
            var entries = MissingMaterialBookmarkList.textEntries(decoded);
            org.junit.jupiter.api.Assertions.assertEquals(1, entries.size());
            org.junit.jupiter.api.Assertions.assertEquals(3, entries.get(0).missingCount());
            org.junit.jupiter.api.Assertions.assertEquals(strict.getTag(), entries.get(0).bookmark().getTag());
        } finally {
            buf.release();
        }
    }

    @Test
    void decodeRejectsNegativeMaterialShortage() {
        FriendlyByteBuf buf = packetPrefix();
        try {
            buf.writeVarInt(0);
            buf.writeVarInt(1);
            com.huanghuang.rsintegration.crafting.tree.IngredientKey.of(
                    new ItemStack(net.minecraft.world.item.Items.IRON_INGOT)).write(buf);
            buf.writeVarInt(2);
            buf.writeVarInt(0);
            buf.writeVarInt(-1);
            assertThrows(DecoderException.class, () -> PlanResponsePacket.decode(buf));
        } finally {
            buf.release();
        }
    }

    @Test
    void decodeRejectsMaliciousStepCountBeforeAllocating() {
        FriendlyByteBuf buf = packetPrefix();
        buf.writeVarInt(Integer.MAX_VALUE);

        Throwable thrown = assertThrows(DecoderException.class,
                () -> PlanResponsePacket.decode(buf));
        assertFalse(thrown instanceof OutOfMemoryError);
    }

    @Test
    void decodeRejectsNegativeStepCount() {
        FriendlyByteBuf buf = packetPrefix();
        buf.writeVarInt(-1);

        assertThrows(DecoderException.class, () -> PlanResponsePacket.decode(buf));
    }

    @Test
    void graphRejectsEveryOversizedCollectionCount() {
        assertGraphCountRejected(buf -> buf.writeVarInt(OVERSIZED_COUNT));
        assertGraphCountRejected(buf -> {
            writeNodeHeader(buf);
            buf.writeVarInt(OVERSIZED_COUNT);
        });
        assertGraphCountRejected(buf -> {
            writeNodeHeader(buf);
            buf.writeVarInt(0);
            buf.writeVarInt(OVERSIZED_COUNT);
        });
        assertGraphCountRejected(buf -> {
            writeNodeHeader(buf);
            buf.writeVarInt(0);
            buf.writeVarInt(0);
            buf.writeVarInt(OVERSIZED_COUNT);
        });
        assertGraphCountRejected(buf -> {
            writeNodeHeader(buf);
            buf.writeVarInt(0);
            buf.writeVarInt(0);
            buf.writeVarInt(0);
            buf.writeVarInt(OVERSIZED_COUNT);
        });
        assertGraphCountRejected(buf -> {
            buf.writeVarInt(0);
            buf.writeVarInt(OVERSIZED_COUNT);
        });
        assertGraphCountRejected(buf -> {
            buf.writeVarInt(0);
            buf.writeVarInt(0);
            buf.writeVarInt(OVERSIZED_COUNT);
        });
        assertGraphCountRejected(buf -> {
            buf.writeVarInt(0);
            buf.writeVarInt(0);
            buf.writeVarInt(1);
            buf.writeItem(ItemStack.EMPTY);
            buf.writeVarInt(0);
            buf.writeVarInt(0);
            buf.writeVarInt(OVERSIZED_COUNT);
        });
        assertGraphCountRejected(buf -> {
            buf.writeVarInt(0);
            buf.writeVarInt(0);
            buf.writeVarInt(0);
            buf.writeVarInt(OVERSIZED_COUNT);
        });
        assertGraphCountRejected(buf -> {
            buf.writeVarInt(0);
            buf.writeVarInt(0);
            buf.writeVarInt(0);
            buf.writeVarInt(0);
            buf.writeVarInt(OVERSIZED_COUNT);
        });
    }

    @Test
    void truncatedGraphFailsInsteadOfReturningPartialView() {
        FriendlyByteBuf buf = buffer();
        buf.writeVarInt(1);
        buf.writeVarInt(1);

        assertThrows(RuntimeException.class, () -> PlanResponsePacket.readGraph(buf));
    }

    @Test
    void decodeRejectsOversizedRecipeIdUtf() {
        FriendlyByteBuf buf = buffer();
        buf.writeBoolean(true);
        buf.writeUtf("x".repeat(257), 32767);

        assertThrows(DecoderException.class, () -> PlanResponsePacket.decode(buf));
    }

    @Test
    void encodeRejectsEveryOversizedBusinessString() {
        assertEncodeRejected(plan("x".repeat(257), "Target", null, null,
                List.of(), List.of(), null, null, Set.of()));
        assertEncodeRejected(plan("test:recipe", "x".repeat(257), null, null,
                List.of(), List.of(), null, null, Set.of()));
        assertEncodeRejected(plan("test:recipe", "Target", "x".repeat(129), null,
                List.of(), List.of(), null, null, Set.of()));
        assertEncodeRejected(plan("test:recipe", "Target", null, "x".repeat(129),
                List.of(), List.of(), null, null, Set.of()));
        assertEncodeRejected(plan("test:recipe", "Target", null, null,
                List.of("x".repeat(2049)), List.of(), null, null, Set.of()));
        // modWarnings / embers name arrays are Components now, not length-capped
        // Strings — their bound is Component encoding, covered by vanilla.
        assertEncodeRejected(plan("test:recipe", "Target", null, null,
                List.of(), List.of(), null, null, Set.of("x".repeat(129))));
    }

    private static PlanResponse plan(String recipeId, String targetName,
                                     String executionModType, String executionDimension,
                                     List<String> missing, List<Component> warnings,
                                     Component[] aspectNames, Component[] inputNames,
                                     Set<String> boundMachineTypes) {
        return new PlanResponse(true, targetName, ItemStack.EMPTY, List.of(), Map.of(),
                missing, recipeId, executionModType, executionDimension, 0, 0, 0,
                warnings, 1, null, aspectNames, inputNames, 0, false, false,
                false, null, boundMachineTypes, Map.of(), null, null);
    }

    private static void assertEncodeRejected(PlanResponse plan) {
        FriendlyByteBuf buf = buffer();
        assertThrows(io.netty.handler.codec.EncoderException.class,
                () -> new PlanResponsePacket(plan).encode(buf));
    }

    private static void assertGraphCountRejected(Consumer<FriendlyByteBuf> writer) {
        FriendlyByteBuf buf = buffer();
        buf.writeVarInt(1);
        writer.accept(buf);
        assertThrows(DecoderException.class, () -> PlanResponsePacket.readGraph(buf));
    }

    private static FriendlyByteBuf packetPrefix() {
        FriendlyByteBuf buf = buffer();
        buf.writeBoolean(true);
        buf.writeUtf("test:recipe");
        buf.writeUtf("Target");
        buf.writeItem(ItemStack.EMPTY);
        return buf;
    }

    private static void writeNodeHeader(FriendlyByteBuf buf) {
        buf.writeVarInt(1);
        buf.writeVarInt(0);
        buf.writeResourceLocation(net.minecraft.resources.ResourceLocation.parse("test:node"));
        buf.writeUtf("generic", 128);
        buf.writeVarInt(1);
        buf.writeItem(ItemStack.EMPTY);
    }

    private static FriendlyByteBuf buffer() {
        return new FriendlyByteBuf(Unpooled.buffer());
    }
}
