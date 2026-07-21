package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.crafting.CraftingResolver;
import com.huanghuang.rsintegration.crafting.OutputDestination;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenericCraftPacketTest extends BootstrapTest {

    @Test
    void synchronousTerminalStepUsesRequestedExecutionCount() {
        var step = GenericCraftPacket.genericTerminalStep(
                new ResourceLocation("minecraft", "iron_ingot"), 6);

        assertEquals(6, step.executions());
    }

    @Test
    void smithingWaitsForAsynchronousIntermediateBeforeTerminalStep() {
        ResourceLocation intermediateId = new ResourceLocation("test", "dark_helmet");
        ResourceLocation smithingId = new ResourceLocation("test", "divine_gold_helmet");
        var intermediate = new CraftingResolver.ResolutionStep(
                intermediateId, ModType.CUSTOM_GUI, new ResourceLocation("test", "machine"));

        List<CraftingResolver.ResolutionStep> chain = GenericCraftPacket.smithingAsyncSteps(
                List.of(intermediate), smithingId, 3);

        assertEquals(2, chain.size());
        assertEquals(intermediateId, chain.get(0).recipeId());
        assertEquals(smithingId, chain.get(1).recipeId());
        assertEquals(ModType.GENERIC, chain.get(1).modType());
        assertEquals(3, chain.get(1).executions());
    }

    @Test
    void genericOnlySmithingIntermediatesStaySynchronous() {
        var intermediate = new CraftingResolver.ResolutionStep(
                new ResourceLocation("test", "dark_helmet"), ModType.GENERIC,
                new ResourceLocation("minecraft", "crafting"));

        assertTrue(GenericCraftPacket.smithingAsyncSteps(
                List.of(intermediate), new ResourceLocation("test", "divine_gold_helmet"), 1)
                .isEmpty());
    }

    @Test
    void outputDestinationRoundTripsAndInvalidOrdinalsDefaultToRs() {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        OutputDestination.PLAYER_INVENTORY.write(buffer);

        assertEquals(OutputDestination.PLAYER_INVENTORY,
                OutputDestination.read(buffer));
        assertEquals(OutputDestination.RS_NETWORK, OutputDestination.byOrdinal(-1));
        assertEquals(OutputDestination.RS_NETWORK, OutputDestination.byOrdinal(99));
    }
}
